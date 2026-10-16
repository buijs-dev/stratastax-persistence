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
package dev.buijs.stratastax.persistence.reactive.internal

import dev.buijs.stratastax.persistence.core.api.EntityNotFoundException
import dev.buijs.stratastax.persistence.core.api.OptimisticLockException
import dev.buijs.stratastax.persistence.core.spi.RowMapper
import dev.buijs.stratastax.persistence.reactive.spi.JooqEntityBinding
import dev.buijs.stratastax.persistence.reactive.spi.QueryExecutor
import dev.buijs.stratastax.persistence.reactive.spi.applyJoins

/** `SELECT <parts' select list> FROM <table> <parts' joins>`, without a condition yet. */
internal fun JooqEntityBinding.selectRows(
    parts: dev.buijs.stratastax.persistence.reactive.spi.JooqSearchParts,
) = dsl.select(parts.selectFields).from(table).applyJoins(parts.joins)

/**
 * Reads the rows with [keys] back through the same projection a read uses (a written row's own
 * columns are not what a [RowMapper] expects), mapped in the order of [keys].
 *
 * @throws IllegalStateException when a written row is not visible to a read - e.g. a default filter
 *   hides it. Thrown inside the write's transaction, so the write is rolled back.
 */
internal suspend fun <T> JooqEntityBinding.reselect(
    executor: QueryExecutor,
    keys: List<Any>,
    rowMapper: RowMapper<T>,
): List<T> {
    if (keys.isEmpty()) return emptyList()
    val parts = queryTranslator.translateByIds(idField, keys, fieldMapping)
    val rows = executor.executeAndGetAll(selectRows(parts).where(parts.condition))
    val byKey = rows.associateBy { it.rawOrNull(idField.name) }

    return keys.map { key ->
        val row =
            byKey[key]
                ?: throw IllegalStateException(
                    "'${table.name}' row '$key' was written but is not visible to a read (does a default filter exclude it?)",
                )
        rowMapper.map(row)
    }
}

/**
 * For each input of an update or delete batch, why it is rejected before any SQL runs, or `null`:
 * a versioned entity needs an expected version, and an id may occur only once per batch.
 */
internal fun JooqEntityBinding.rejectedInputs(
    keys: List<Any>,
    expectedVersions: List<Any?>,
): List<Throwable?> {
    val seen = HashSet<Any>()
    return keys.indices.map { index ->
        when {
            version != null && expectedVersions[index] == null -> {
                IllegalArgumentException(
                    "'${table.name}' is versioned: an expected version is required for id '${keys[index]}'",
                )
            }
            !seen.add(keys[index]) -> {
                IllegalArgumentException("Id '${keys[index]}' occurs more than once in this batch")
            }
            else -> {
                null
            }
        }
    }
}

/**
 * Why the write of row [key] affected nothing: [EntityNotFoundException] when there is no such
 * (not soft-deleted) row, [OptimisticLockException] when there is but [expectedVersion] is stale.
 */
internal suspend fun JooqEntityBinding.diagnoseMiss(
    executor: QueryExecutor,
    key: Any,
    expectedVersion: Any?,
): Exception {
    if (version == null) return EntityNotFoundException(table.name, key)
    val exists =
        executor.exists(
            dsl
                .selectOne()
                .from(table)
                .where(allOf(idColumn.untyped().eq(key), activeRows(softDeleteColumn)))
                .limit(1),
        )
    return if (exists) {
        OptimisticLockException(
            table.name,
            key,
            expectedVersion,
        )
    } else {
        EntityNotFoundException(table.name, key)
    }
}
