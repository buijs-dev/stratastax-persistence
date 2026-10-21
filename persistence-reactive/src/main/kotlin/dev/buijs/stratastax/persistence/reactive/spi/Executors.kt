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
import dev.buijs.stratastax.persistence.core.spi.RowContext

/**
 * Runs SQL and hands back its rows as [RowContext]s: the one place where SQL actually runs. SQL is
 * text with numbered placeholders (`$1, $2, ...`) and its parameters; the statements generated
 * code holds as literal constants, and handwritten ones alike.
 */
public interface QueryExecutor {

    /**
     * Every row [sql] returns, with [params] bound to its placeholders in order: `$1` is
     * `params[0]`. A [Map] parameter is bound as a JSON object; a SQL array is bound from a typed
     * array (`Array<UUID>`, `Array<Long>`, ...).
     */
    public suspend fun query(
        sql: String,
        params: List<Any?> = emptyList(),
    ): List<RowContext>

    /** The first row [sql] returns, or `null`. [sql] should limit itself to one row. */
    public suspend fun queryFirst(
        sql: String,
        params: List<Any?> = emptyList(),
    ): RowContext?

    /** The number of rows an `INSERT`/`UPDATE`/`DELETE` [sql] affected. */
    public suspend fun execute(
        sql: String,
        params: List<Any?> = emptyList(),
    ): Int

    /**
     * Runs [sql] once per parameter set of [batch], all in one round trip, and returns the rows each
     * run returned (empty for a run without `RETURNING` or that matched nothing), in the order of
     * [batch]. An empty [batch] runs nothing.
     */
    public suspend fun batch(
        sql: String,
        batch: List<List<Any?>>,
    ): List<List<RowContext>>
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
