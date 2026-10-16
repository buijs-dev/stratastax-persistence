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

import dev.buijs.stratastax.persistence.core.api.BatchItemResult
import dev.buijs.stratastax.persistence.core.api.BatchResult
import dev.buijs.stratastax.persistence.core.api.DeleteAccess
import dev.buijs.stratastax.persistence.core.api.DeleteBatchItem
import dev.buijs.stratastax.persistence.core.spi.itemResultOf
import dev.buijs.stratastax.persistence.core.spi.runCatchingNonCancellation
import dev.buijs.stratastax.persistence.core.spi.toItemResult
import dev.buijs.stratastax.persistence.reactive.internal.KEY_ALIAS
import dev.buijs.stratastax.persistence.reactive.internal.RowWriter
import dev.buijs.stratastax.persistence.reactive.internal.activeRows
import dev.buijs.stratastax.persistence.reactive.internal.allOf
import dev.buijs.stratastax.persistence.reactive.internal.cascadeRemove
import dev.buijs.stratastax.persistence.reactive.internal.diagnoseMiss
import dev.buijs.stratastax.persistence.reactive.internal.isOneOf
import dev.buijs.stratastax.persistence.reactive.internal.rejectedInputs
import dev.buijs.stratastax.persistence.reactive.internal.removeOwnedChildren
import dev.buijs.stratastax.persistence.reactive.internal.removeRows
import dev.buijs.stratastax.persistence.reactive.internal.returnedAlias
import dev.buijs.stratastax.persistence.reactive.internal.rowsPerStatement
import dev.buijs.stratastax.persistence.reactive.internal.selectValues
import dev.buijs.stratastax.persistence.reactive.internal.untyped
import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL

/**
 * [DeleteAccess] over jOOQ: a generated `${Entity}DeleteAccess` supplies [binding] and the
 * relations to remove with the root row.
 *
 * With a [JooqEntityBinding.softDeleteColumn] a delete sets it instead of deleting the row; an
 * already soft-deleted row counts as not found. Every delete - one item or a batch - runs in one
 * transaction:
 * 1. lock the root rows and read the keys their relations need;
 * 2. remove the links of [deleteJunctionWrites] (only on a hard delete - a soft-deleted row keeps
 *    them, so it can be restored) and the children of [deleteOneToManyCascades];
 * 3. delete (or soft-delete) the root rows, checking and incrementing the version;
 * 4. remove the owned one-to-one rows of [deleteOwnedChildWrites].
 */
public interface JooqDeleteAccess<ID> : DeleteAccess<ID> {

    public val binding: JooqEntityBinding

    /** Owned one-to-one rows removed after the root row. */
    public val deleteOwnedChildWrites: List<OwnedChildDeleteWrite>
        get() = emptyList()

    /** One-to-many children removed before the root row. */
    public val deleteOneToManyCascades: List<OneToManyCascade>
        get() = emptyList()

    /** Many-to-many links removed before the root row. */
    public val deleteJunctionWrites: List<JunctionDeleteWrite>
        get() = emptyList()

    /** The id column's value for [id]; override when [ID] wraps it (e.g. a value class). */
    public fun mapId(id: ID): Any = id as Any

    override suspend fun delete(
        id: ID,
        expectedVersion: Any?,
    ) {
        binding.transactionalExecutor.withTransaction { executor ->
            deleteRows(executor, listOf(DeleteBatchItem(id, expectedVersion))).single().getOrThrow()
        }
    }

    /**
     * Deletes [items] in one go (see the steps above); an item that matched no row, or that is
     * rejected up front, gets its own failure. When the batch as a whole fails, nothing of it is
     * kept and every item is deleted on its own instead.
     */
    override suspend fun deleteAll(items: List<DeleteBatchItem<ID>>): BatchResult<Unit> {
        if (items.isEmpty()) return BatchResult(emptyList())
        return runCatchingNonCancellation {
            binding.transactionalExecutor.withTransaction { deleteRows(it, items) }
        }.fold(
            onSuccess = { results -> BatchResult(results.map { it.toItemResult() }) },
            onFailure = { error ->
                if (items.size == 1) {
                    BatchResult(listOf(BatchItemResult.Failure(error)))
                } else {
                    val rejected =
                        binding.rejectedInputs(
                            items.map { mapId(it.id) },
                            items.map { it.expectedVersion },
                        )
                    BatchResult(
                        items.mapIndexed { index, item ->
                            rejected[index]?.let { BatchItemResult.Failure(it) }
                                ?: itemResultOf { delete(item.id, item.expectedVersion) }
                        },
                    )
                }
            },
        )
    }
}

private suspend fun <ID> JooqDeleteAccess<ID>.deleteRows(
    executor: QueryExecutor,
    items: List<DeleteBatchItem<ID>>,
): List<Result<Unit>> {
    val writer = RowWriter(binding.dsl, executor)
    val keys = items.map { mapId(it.id) }

    val rejected = binding.rejectedInputs(keys, items.map { it.expectedVersion })
    val candidates = items.indices.filter { rejected[it] == null }

    val deleted = HashSet<Any?>()
    candidates.chunked(rowsPerStatement(2)).forEach { chunk ->
        deleted.addAll(deleteChunk(writer, chunk.map { keys[it] to items[it].expectedVersion }))
    }

    return items.indices.map { index ->
        rejected[index]?.let { Result.failure(it) }
            ?: if (keys[index] in deleted) {
                Result.success(Unit)
            } else {
                Result.failure(
                    binding.diagnoseMiss(executor, keys[index], items[index].expectedVersion),
                )
            }
    }
}

private suspend fun <ID> JooqDeleteAccess<ID>.deleteChunk(
    writer: RowWriter,
    targets: List<Pair<Any, Any?>>,
): Set<Any?> {
    val table = binding.table
    val softDelete = binding.softDeleteColumn
    val matching = allOf(rootsMatching(targets), activeRows(softDelete))
    val hasRelations =
        deleteJunctionWrites.isNotEmpty() || deleteOneToManyCascades.isNotEmpty() ||
            deleteOwnedChildWrites.isNotEmpty()
    val related = if (hasRelations) readRelated(writer, matching) else emptyList()
    val keys = related.map { it.getValue(binding.idColumn) }.toSet()

    if (softDelete == null) {
        deleteJunctionWrites.forEach { junction ->
            val roots = related.mapNotNull { it[junction.parentRootColumn] }

            if (roots.isEmpty()) return@forEach
            val ofRoots = junction.rootColumn.isOneOf(roots)
            val targetIds =
                junction.cascadeTarget
                    ?.let {
                        writer.selectValues(junction.table, it.junctionTargetColumn, ofRoots)
                    }.orEmpty()
            writer.executor.executeUpdate(writer.dsl.deleteFrom(junction.table).where(ofRoots))
            junction.cascadeTarget?.let { target ->
                if (targetIds.isNotEmpty()) {
                    writer.removeRows(
                        target.table,
                        null,
                        target.targetColumn.isOneOf(targetIds),
                    )
                }
            }
        }
    }

    deleteOneToManyCascades.forEach { cascade ->
        val parents = related.mapNotNull { it[cascade.shape.parentRootColumn] }.toSet()
        writer.cascadeRemove(listOf(cascade), parents, parentSoftDeleted = softDelete != null)
    }

    val removed =
        if (softDelete != null) {
            val assignments: Map<Field<*>, Any?> =
                mapOf<Field<*>, Any?>(softDelete to DSL.currentOffsetDateTime()) +
                    listOfNotNull(
                        binding.version?.let { it.column to it.increment.next(it.column) },
                    ).toMap()
            writer.executor.executeAndGetAll(
                writer.dsl
                    .update(table)
                    .set(assignments)
                    .where(matching)
                    .returning(binding.idColumn.`as`(KEY_ALIAS)),
            )
        } else {
            writer.executor.executeAndGetAll(
                writer.dsl
                    .deleteFrom(
                        table,
                    ).where(matching)
                    .returning(binding.idColumn.`as`(KEY_ALIAS)),
            )
        }.map { it.rawOrNull(KEY_ALIAS) }.toSet()
    check(!hasRelations || removed == keys) {
        "'${table.name}' rows changed while they were locked for deletion"
    }

    val owned =
        deleteOwnedChildWrites.associateWith { write ->
            related.mapNotNull { it[write.parentFkColumn] }.toSet()
        }

    writer.removeOwnedChildren(owned, parentSoftDeleted = softDelete != null)
    return removed
}

/** The root rows to delete: by id, and by id and version for a versioned entity. */
private fun <ID> JooqDeleteAccess<ID>.rootsMatching(targets: List<Pair<Any, Any?>>): Condition {
    val version = binding.version ?: return binding.idColumn.isOneOf(targets.map { it.first })
    val pairs = targets.map { (key, expected) -> DSL.row(key, expected) }

    return DSL.row(binding.idColumn.untyped(), version.column.untyped()).`in`(pairs)
}

/**
 * Locks the root rows matching [condition] and reads, per row, its id and the columns its
 * relations are keyed by - before the rows are removed and those values are gone.
 */
private suspend fun <ID> JooqDeleteAccess<ID>.readRelated(
    writer: RowWriter,
    condition: Condition,
): List<Map<org.jooq.TableField<*, *>, Any?>> {
    val columns =
        (
            listOf(binding.idColumn) +
                deleteJunctionWrites.map { it.parentRootColumn } +
                deleteOneToManyCascades.map { it.shape.parentRootColumn } +
                deleteOwnedChildWrites.map { it.parentFkColumn }
        ).distinct()
    val rows =
        writer.executor.executeAndGetAll(
            writer.dsl
                .select(columns.map { it.`as`(returnedAlias(it)) })
                .from(binding.table)
                .where(condition)
                .forUpdate(),
        )
    return rows.map { row -> columns.associateWith { row.rawOrNull(returnedAlias(it)) } }
}
