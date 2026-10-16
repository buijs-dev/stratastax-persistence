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
import dev.buijs.stratastax.persistence.core.api.CreateAccess
import dev.buijs.stratastax.persistence.core.spi.CreateMapper
import dev.buijs.stratastax.persistence.core.spi.RowMapper
import dev.buijs.stratastax.persistence.core.spi.itemResultOf
import dev.buijs.stratastax.persistence.core.spi.runCatchingNonCancellation
import dev.buijs.stratastax.persistence.reactive.internal.KEY_ALIAS
import dev.buijs.stratastax.persistence.reactive.internal.Parent
import dev.buijs.stratastax.persistence.reactive.internal.RowWriter
import dev.buijs.stratastax.persistence.reactive.internal.createChildren
import dev.buijs.stratastax.persistence.reactive.internal.createOwnedChildren
import dev.buijs.stratastax.persistence.reactive.internal.insertReturning
import dev.buijs.stratastax.persistence.reactive.internal.mergeDisjoint
import dev.buijs.stratastax.persistence.reactive.internal.reselect
import dev.buijs.stratastax.persistence.reactive.internal.resolveForeignKeys
import dev.buijs.stratastax.persistence.reactive.internal.writeJunctions

/**
 * [CreateAccess] over jOOQ: a generated `${Entity}CreateAccess` supplies [binding], [rowMapper],
 * [createMapper] and the relations to write with the root row.
 *
 * Every create - one command or a batch - runs the same steps in one transaction:
 * 1. insert the owned one-to-one rows ([createOwnedChildWrites]), one batch per relation;
 * 2. resolve the public ids of [createFkResolves], one query per relation;
 * 3. insert the root rows in one multi-row `INSERT ... RETURNING`;
 * 4. insert the links of [createJunctionWrites] and the children of [createOneToManyWrites], one
 *    batch per relation;
 * 5. read the rows back through the read projection, one query, and map them with [rowMapper].
 */
public interface JooqCreateAccess<C, T> : CreateAccess<C, T> {

    public val binding: JooqEntityBinding

    public val rowMapper: RowMapper<T>

    public val createMapper: CreateMapper<C, CreateValues>

    /** Many-to-many links written with the root row. */
    public val createJunctionWrites: List<JunctionWrite<C>>
        get() = emptyList()

    /** One-to-one relations the command references by public id. */
    public val createFkResolves: List<GroupFkResolve<C>>
        get() = emptyList()

    /** One-to-many children inserted with the root row. */
    public val createOneToManyWrites: List<OneToManyCreateWrite<C, *>>
        get() = emptyList()

    /** Owned one-to-one rows inserted before the root row, which references them. */
    public val createOwnedChildWrites: List<OwnedChildCreateWrite<C, *>>
        get() = emptyList()

    override suspend fun create(command: C): T =
        binding.transactionalExecutor.withTransaction { executor ->
            createRows(executor, listOf(command)).single()
        }

    /**
     * Creates [commands] in one go (see the steps above). When that fails, nothing of it is kept
     * and every command is created on its own instead, so each one reports its own outcome.
     */
    override suspend fun createAll(commands: List<C>): BatchResult<T> {
        if (commands.isEmpty()) return BatchResult(emptyList())
        return runCatchingNonCancellation {
            binding.transactionalExecutor.withTransaction { createRows(it, commands) }
        }.fold(
            onSuccess = { values -> BatchResult(values.map { BatchItemResult.Success(it) }) },
            onFailure = { error ->
                if (commands.size == 1) {
                    BatchResult(listOf(BatchItemResult.Failure(error)))
                } else {
                    BatchResult(commands.map { itemResultOf { create(it) } })
                }
            },
        )
    }
}

private suspend fun <C, T> JooqCreateAccess<C, T>.createRows(
    executor: QueryExecutor,
    commands: List<C>,
): List<T> {
    val writer = RowWriter(binding.dsl, executor)
    val owned = writer.createOwnedChildren(createOwnedChildWrites, commands)
    val foreignKeys = writer.resolveForeignKeys(createFkResolves, commands)
    val rows =
        commands.indices.map { index ->
            mergeDisjoint(
                createMapper.map(commands[index]).values,
                foreignKeys[index],
                owned[index],
            )
        }

    val returned =
        createJunctionWrites.map { it.parentRootColumn } +
            createOneToManyWrites.map { it.parentRootColumn }

    val inserted = writer.insertReturning(binding.table, binding.idColumn, rows, returned)
    val parents = commands.zip(inserted) { command, row -> Parent(command, row) }

    writer.writeJunctions(createJunctionWrites, parents, replace = false)
    createOneToManyWrites.forEach { write ->
        writer.createChildren(
            write,
            parents.map {
                it.command to
                    it.value(write.parentRootColumn)
            },
        )
    }

    val keys =
        inserted.map {
            checkNotNull(
                it.rawOrNull(KEY_ALIAS),
            ) { "An inserted '${binding.table.name}' row has no id" }
        }

    return binding.reselect(executor, keys, rowMapper)
}
