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
import dev.buijs.stratastax.persistence.core.api.UpdateAccess
import dev.buijs.stratastax.persistence.core.api.UpdateBatchItem
import dev.buijs.stratastax.persistence.core.spi.RowMapper
import dev.buijs.stratastax.persistence.core.spi.UpdateMapper
import dev.buijs.stratastax.persistence.core.spi.itemResultOf
import dev.buijs.stratastax.persistence.core.spi.runCatchingNonCancellation
import dev.buijs.stratastax.persistence.core.spi.toItemResult
import dev.buijs.stratastax.persistence.reactive.internal.KEY_ALIAS
import dev.buijs.stratastax.persistence.reactive.internal.KeyedRow
import dev.buijs.stratastax.persistence.reactive.internal.Parent
import dev.buijs.stratastax.persistence.reactive.internal.RowWriter
import dev.buijs.stratastax.persistence.reactive.internal.activeRows
import dev.buijs.stratastax.persistence.reactive.internal.diagnoseMiss
import dev.buijs.stratastax.persistence.reactive.internal.mergeDisjoint
import dev.buijs.stratastax.persistence.reactive.internal.rejectedInputs
import dev.buijs.stratastax.persistence.reactive.internal.reselect
import dev.buijs.stratastax.persistence.reactive.internal.resolveForeignKeys
import dev.buijs.stratastax.persistence.reactive.internal.synchronizeChildren
import dev.buijs.stratastax.persistence.reactive.internal.updateRows
import dev.buijs.stratastax.persistence.reactive.internal.writeJunctions

/**
 * [UpdateAccess] over jOOQ: a generated `{Entity}UpdateAccess` supplies [binding], [rowMapper],
 * [updateMapper] and the relations to synchronize.
 *
 * Every update - one item or a batch - runs the same steps in one transaction:
 * 1. resolve the public ids of [updateFkResolves], one query per relation;
 * 2. update the root rows - items writing the same columns share one statement - checking and
 *    incrementing the version of a versioned entity, skipping soft-deleted rows;
 * 3. synchronize the links of [updateJunctionWrites] and the children of [updateOneToManyWrites];
 * 4. read the rows back through the read projection and map them with [rowMapper].
 *
 * An item that matched no row is diagnosed as
 * [dev.buijs.stratastax.persistence.core.api.EntityNotFoundException] or
 * [dev.buijs.stratastax.persistence.core.api.OptimisticLockException].
 */
public interface JooqUpdateAccess<ID, U, T> : UpdateAccess<ID, U, T> {

    public val binding: JooqEntityBinding

    public val rowMapper: RowMapper<T>

    public val updateMapper: UpdateMapper<U, UpdateValues>

    /** Many-to-many links synchronized with the command. */
    public val updateJunctionWrites: List<JunctionWrite<U>>
        get() = emptyList()

    /** One-to-one relations the command references by public id. */
    public val updateFkResolves: List<GroupFkResolve<U>>
        get() = emptyList()

    /** One-to-many children synchronized with the command. */
    public val updateOneToManyWrites: List<OneToManyUpdateWrite<U, *>>
        get() = emptyList()

    /** The id column's value for [id]; override when [ID] wraps it (e.g. a value class). */
    public fun mapId(id: ID): Any = id as Any

    override suspend fun update(
        id: ID,
        command: U,
        expectedVersion: Any?,
    ): T =
        binding.transactionalExecutor.withTransaction { executor ->
            updateRows(
                executor,
                listOf(UpdateBatchItem(id, command, expectedVersion)),
            ).single().getOrThrow()
        }

    /**
     * Updates [items] in one go (see the steps above); an item that matched no row, or that is
     * rejected up front, gets its own failure. When the batch as a whole fails (e.g. a missing
     * referenced entity), nothing of it is kept and every item is updated on its own instead.
     */
    override suspend fun updateAll(items: List<UpdateBatchItem<ID, U>>): BatchResult<T> {
        if (items.isEmpty()) return BatchResult(emptyList())
        return runCatchingNonCancellation {
            binding.transactionalExecutor.withTransaction { updateRows(it, items) }
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
                                ?: itemResultOf {
                                    update(
                                        item.id,
                                        item.command,
                                        item.expectedVersion,
                                    )
                                }
                        },
                    )
                }
            },
        )
    }
}

private suspend fun <ID, U, T> JooqUpdateAccess<ID, U, T>.updateRows(
    executor: QueryExecutor,
    items: List<UpdateBatchItem<ID, U>>,
): List<Result<T>> {
    val writer = RowWriter(binding.dsl, executor)
    val keys = items.map { mapId(it.id) }

    val rejected = binding.rejectedInputs(keys, items.map { it.expectedVersion })
    val candidates = items.indices.filter { rejected[it] == null }

    val foreignKeys =
        writer.resolveForeignKeys(
            updateFkResolves,
            candidates.map { items[it].command },
        )
    val rows =
        candidates.mapIndexed { position, index ->
            KeyedRow(
                keys[index],
                items[index].expectedVersion,
                mergeDisjoint(updateMapper.map(items[index].command).values, foreignKeys[position]),
            )
        }

    val updated =
        writer
            .updateRows(
                table = binding.table,
                key = binding.idColumn,
                rows = rows,
                version = binding.version?.let { it.column to it.increment.next(it.column) },
                condition = activeRows(binding.softDeleteColumn),
                returned =
                    updateJunctionWrites.map { it.parentRootColumn } +
                        updateOneToManyWrites.map { it.shape.parentRootColumn },
            ).associateBy { it.rawOrNull(KEY_ALIAS) }

    val matched = candidates.filter { keys[it] in updated }

    val parents = matched.map { Parent(items[it].command, updated.getValue(keys[it])) }

    writer.writeJunctions(updateJunctionWrites, parents, replace = true)
    updateOneToManyWrites.forEach { write ->
        writer.synchronizeChildren(
            write,
            parents.map {
                it.command to
                    it.value(write.shape.parentRootColumn)
            },
        )
    }

    val entities =
        matched
            .zip(
                binding.reselect(executor, matched.map { keys[it] }, rowMapper),
            ).toMap()
    return items.indices.map { index ->
        rejected[index]?.let { Result.failure(it) }
            ?: entities[index]?.let { Result.success(it) }
            ?: Result.failure(
                binding.diagnoseMiss(executor, keys[index], items[index].expectedVersion),
            )
    }
}
