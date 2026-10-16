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

import dev.buijs.stratastax.persistence.core.api.TransactionOptions
import dev.buijs.stratastax.persistence.core.api.UnitOfWork
import dev.buijs.stratastax.persistence.core.spi.RenderedQuery
import dev.buijs.stratastax.persistence.core.spi.RowContext
import org.jooq.Query

/**
 * Runs a jOOQ [Query] and hands back its rows as [RowContext]s. jOOQ only builds the SQL; this is
 * the one place where SQL actually runs.
 */
public interface QueryExecutor {

    /** Every row [query] returns. */
    public suspend fun executeAndGetAll(query: Query): List<RowContext>

    /** The first row [query] returns, or `null`. [query] should limit itself to one row. */
    public suspend fun executeAndGetFirst(query: Query): RowContext?

    /** Whether [query] returns any row. [query] should limit itself to one row. */
    public suspend fun exists(query: Query): Boolean

    /** The first column of the first row of a `SELECT count(*) ...` [query], `0` without rows. */
    public suspend fun executeAndGetCount(query: Query): Long

    /** The number of rows a plain (non-`RETURNING`) `INSERT`/`UPDATE`/`DELETE` affected. */
    public suspend fun executeUpdate(query: Query): Int

    /**
     * The row an `INSERT`/`UPDATE ... RETURNING` [query] returned, or `null` when it affected no
     * row.
     */
    public suspend fun executeReturning(query: Query): RowContext?
}

/**
 * Runs a block against a single transactional connection: [withTransaction] hands the block a
 * [QueryExecutor] bound to that connection and commits when the block completes, or rolls back
 * when it throws.
 *
 * With [dev.buijs.stratastax.persistence.core.api.TransactionPropagation.REQUIRED] (the default),
 * a call made while a transaction of this executor is running joins it inside a savepoint: the
 * block's failure only undoes its own work, and the caller can carry on with the outer transaction.
 */
public interface TransactionalExecutor : UnitOfWork {

    /** Runs [block] in a transaction with [TransactionOptions.DEFAULT]. */
    public suspend fun <T> withTransaction(block: suspend (QueryExecutor) -> T): T =
        withTransaction(TransactionOptions.DEFAULT, block)

    /** Runs [block] in a transaction configured by [options]. */
    public suspend fun <T> withTransaction(
        options: TransactionOptions,
        block: suspend (QueryExecutor) -> T,
    ): T

    /** Whether the calling coroutine runs inside a transaction of this executor. */
    public suspend fun isInTransaction(): Boolean

    override suspend fun <T> transaction(
        options: TransactionOptions,
        block: suspend () -> T,
    ): T = withTransaction(options) { block() }
}

/** Renders a jOOQ [Query] to the SQL text and bind values a driver sends to the database. */
public fun interface SqlRenderer {

    public fun render(query: Query): RenderedQuery
}
