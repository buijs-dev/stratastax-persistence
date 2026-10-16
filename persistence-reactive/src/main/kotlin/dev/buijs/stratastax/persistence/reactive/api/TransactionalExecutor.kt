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
package dev.buijs.stratastax.persistence.reactive.api

import dev.buijs.stratastax.persistence.core.api.TransactionOptions
import dev.buijs.stratastax.persistence.core.api.UnitOfWork

/**
 * Runs [block] against a single transactional connection - implementations hand [block] a
 * [QueryExecutor] scoped to that one connection/transaction, and commit/roll back based on whether
 * [block] completes normally or throws.
 *
 * With [TransactionOptions.propagation] `REQUIRED` (the default) a call made while a transaction of
 * the same executor is already running joins it instead of opening a second one: [block] then runs
 * inside a savepoint of that transaction, so its failure only undoes its own work and the caller
 * can still carry on with the outer transaction.
 */
interface TransactionalExecutor : UnitOfWork {

    suspend fun <T> withTransaction(block: suspend (QueryExecutor) -> T): T =
        withTransaction(TransactionOptions.DEFAULT, block)

    suspend fun <T> withTransaction(
        options: TransactionOptions,
        block: suspend (QueryExecutor) -> T,
    ): T

    override suspend fun <T> transaction(
        options: TransactionOptions,
        block: suspend () -> T,
    ): T = withTransaction(options) { block() }
}
