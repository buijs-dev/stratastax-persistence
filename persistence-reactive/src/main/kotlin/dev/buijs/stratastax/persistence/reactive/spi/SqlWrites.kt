/**
 * Copyright (c) 2021 - 2026 Buijs Software
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package dev.buijs.stratastax.persistence.reactive.spi

import dev.buijs.stratastax.entity.core.api.PatchField
import dev.buijs.stratastax.persistence.core.api.BatchItemResult
import dev.buijs.stratastax.persistence.core.api.BatchResult
import dev.buijs.stratastax.persistence.core.api.EntityNotFoundException
import dev.buijs.stratastax.persistence.core.api.OptimisticLockException
import dev.buijs.stratastax.persistence.core.api.ReferencedEntityNotFoundException
import dev.buijs.stratastax.persistence.core.spi.RowContext
import dev.buijs.stratastax.persistence.core.spi.itemResultOf
import dev.buijs.stratastax.persistence.core.spi.runCatchingNonCancellation
import dev.buijs.stratastax.persistence.core.spi.toItemResult
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * The outcome of a batch write per input: [all] writes every input at once (inside a transaction
 * the caller starts) and reports each one's result. When it fails as a whole, nothing of it is
 * kept and every input is written on its own through [each] instead, so each one reports its own
 * outcome, except the inputs [rejected] up front (see [rejectedInputs]), which fail as they are.
 */
public suspend fun <I, T> batchOrEach(
    inputs: List<I>,
    all: suspend (List<I>) -> List<Result<T>>,
    each: suspend (I) -> T,
    rejected: List<Throwable?> = inputs.map { null },
): BatchResult<T> {
    if (inputs.isEmpty()) return BatchResult(emptyList())
    return runCatchingNonCancellation { all(inputs) }.fold(
        onSuccess = { results -> BatchResult(results.map { it.toItemResult() }) },
        onFailure = { error ->
            if (inputs.size == 1) {
                BatchResult(listOf(BatchItemResult.Failure(error)))
            } else {
                BatchResult(
                    inputs.mapIndexed { index, input ->
                        rejected[index]?.let { BatchItemResult.Failure(it) }
                            ?: itemResultOf { each(input) }
                    },
                )
            }
        },
    )
}

/**
 * The optimistic-lock version column of an entity: its [column] name and the Java [type] an
 * expected version is converted to.
 */
public class SqlVersion(
    public val column: String,
    public val type: Class<*> = Long::class.javaObjectType,
) {

    /**
     * [expected] as a value of [type] (e.g., the `Long` of an `If-Match` header's text) or `null`
     * when it is `null` or not a valid value.
     */
    public fun valueOf(expected: Any?): Any? {
        if (expected == null || type.isInstance(expected)) return expected

        val text = expected.toString().trim()
        return runCatching {
            when (type) {
                Long::class.javaObjectType -> BigDecimal(text).longValueExact()
                Int::class.javaObjectType -> BigDecimal(text).intValueExact()
                Short::class.javaObjectType -> BigDecimal(text).shortValueExact()
                BigInteger::class.java -> BigDecimal(text).toBigIntegerExact()
                BigDecimal::class.java -> BigDecimal(text)
                OffsetDateTime::class.java -> OffsetDateTime.parse(text)
                LocalDateTime::class.java -> LocalDateTime.parse(text)
                Instant::class.java -> Instant.parse(text)
                else -> null
            }
        }.getOrNull()
    }
}

/**
 * For each input of an update or delete batch on [table], why it is rejected before any SQL runs,
 * or `null`: a versioned entity ([version] not `null`) needs a valid expected version, and an id
 * may occur only once per batch.
 */
public fun rejectedInputs(
    table: String,
    ids: List<Any>,
    expectedVersions: List<Any?>,
    version: SqlVersion?,
): List<Throwable?> {
    val seen = HashSet<Any>()
    return ids.indices.map { index ->
        when {
            version != null && expectedVersions[index] == null -> {
                IllegalArgumentException(
                    "'$table' is versioned: an expected version is required for id '${ids[index]}'",
                )
            }
            version != null && version.valueOf(expectedVersions[index]) == null -> {
                IllegalArgumentException(
                    "Expected version '${expectedVersions[index]}' of id '${ids[index]}' " +
                        "is not a valid '${version.column}' value",
                )
            }
            !seen.add(ids[index]) -> {
                IllegalArgumentException("Id '${ids[index]}' occurs more than once in this batch")
            }
            else -> {
                null
            }
        }
    }
}

/**
 * Why a keyed write of row [id] in [table] affected nothing: [EntityNotFoundException] when
 * [activeRow] (a generated `SELECT 1 FROM <table> WHERE <id> = $1 [AND <not soft-deleted>]`)
 * finds no row, [OptimisticLockException] when it does (so [expectedVersion] is stale). Without a
 * version the row can only be missing.
 */
public suspend fun QueryExecutor.diagnoseMiss(
    table: String,
    activeRow: String,
    id: Any,
    expectedVersion: Any?,
    versioned: Boolean,
): Exception =
    if (versioned && queryFirst(activeRow, listOf(id)) != null) {
        OptimisticLockException(table, id, expectedVersion)
    } else {
        EntityNotFoundException(table, id)
    }

/**
 * A row a command references by its public id: [value] must be a [column] value of a row in
 * [table]. [exists] (a generated `SELECT 1 FROM <table> WHERE <column> = $1`) checks that. A
 * `null` [value] references nothing.
 */
public class Reference(
    public val table: String,
    public val column: String,
    public val exists: String,
    public val value: Any?,
)

/**
 * Checks [references] one by one; for a write that matched no row, to tell whether a missing
 * referenced row is why.
 *
 * @throws ReferencedEntityNotFoundException for the first reference whose row does not exist.
 */
public suspend fun QueryExecutor.requireReferences(references: List<Reference>) {
    references.forEach { reference ->
        val value = reference.value ?: return@forEach
        if (queryFirst(reference.exists, listOf(value)) == null) {
            throw ReferencedEntityNotFoundException(reference.table, reference.column, value)
        }
    }
}

/**
 * The row a guarded write returned - an `INSERT ... SELECT ... WHERE <references exist>` or an
 * `UPDATE ... WHERE <references exist>` - for [table]. When it returned none, a missing referenced
 * row is why (see [requireReferences]).
 *
 * @throws ReferencedEntityNotFoundException for the first of [references] whose row does not exist.
 * @throws IllegalStateException when the write returned no row although every referenced row exists.
 */
public suspend fun QueryExecutor.requireWritten(
    rows: List<RowContext>,
    table: String,
    references: List<Reference>,
): RowContext {
    rows.singleOrNull()?.let { return it }

    requireReferences(references)
    throw IllegalStateException("A write to '$table' returned ${rows.size} rows, expected 1")
}

/**
 * Checks, in one query, that every one of [values] is a [column] value of a row in [table]:
 * [existing] (a generated `SELECT <column> FROM <table> WHERE <column> = ANY($1::<type>[])`)
 * returns the values that are. [values] is a typed array, e.g. `ids.toTypedArray()`.
 *
 * @throws ReferencedEntityNotFoundException for the first value without a row.
 */
public suspend fun QueryExecutor.requireAllReferenced(
    table: String,
    column: String,
    existing: String,
    values: Array<out Any>,
) {
    if (values.isEmpty()) return
    val found = query(existing, listOf(values)).map { it.rawOrNull(column) }.toSet()
    values
        .firstOrNull {
            it !in found
        }?.let { throw ReferencedEntityNotFoundException(table, column, it) }
}

/**
 * What an update writes to the child rows of a one-to-many relation, matched on their id [K]:
 *
 * @property update the elements whose id already has a row; a soft-deleted one included, which
 *   the update restores.
 * @property insert the elements without a row.
 * @property remove the ids of the active rows no element names any more.
 */
public class ChildDiff<E, K : Any>(
    public val update: List<E>,
    public val insert: List<E>,
    public val remove: Set<K>,
)

/**
 * Matches [elements] (by [idOf]) with the existing child rows: [existingIds] holds every row's id,
 * soft-deleted rows included; [activeIds] those of the rows that are not soft-deleted.
 */
public fun <E, K : Any> childDiff(
    elements: List<E>,
    idOf: (E) -> K,
    existingIds: Set<K>,
    activeIds: Set<K>,
): ChildDiff<E, K> {
    val (update, insert) = elements.partition { idOf(it) in existingIds }

    return ChildDiff(update, insert, activeIds - elements.map(idOf).toSet())
}

/**
 * These rows in the order of [keys], matched on their [column] value: how the rows a write reads
 * back (`WHERE <id> = ANY($1)`) are put in input order.
 *
 * @throws IllegalStateException when a written row is not among them - e.g., a default condition
 *   hides it. Thrown inside the write's transaction, so the write is rolled back.
 */
public fun List<RowContext>.inKeyOrder(
    keys: List<Any>,
    column: String,
    table: String,
): List<RowContext> {
    val byKey = associateBy { it.rawOrNull(column) }

    return keys.map { key ->
        byKey[key]
            ?: throw IllegalStateException(
                "'$table' row '$key' was written but is not visible to a read (does a default filter exclude it?)",
            )
    }
}

/**
 * The two parameters of this patch in a generated `UPDATE ... SET c = CASE WHEN $n THEN $m ELSE c
 * END`: whether it is set, and its value, [transform]ed (e.g. a value class unwrapped). An unset
 * patch leaves the column as it is.
 */
public fun <T> PatchField<T>.patchParams(transform: (T & Any) -> Any? = { it }): List<Any?> =
    when (this) {
        PatchField.Unset -> listOf(false, null)
        is PatchField.Value -> listOf(true, value?.let(transform))
    }

/**
 * This collection as the typed array a SQL array parameter is bound from (`$1::text[]`), `null` for
 * `null`. Typed at the call site, so an empty collection binds as an empty array of its type too.
 */
public inline fun <reified T> Collection<T>?.toSqlArray(): Array<T>? = this?.toTypedArray()
