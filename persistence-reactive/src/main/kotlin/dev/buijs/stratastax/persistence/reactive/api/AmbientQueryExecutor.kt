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

import dev.buijs.stratastax.persistence.core.spi.RowContext
import dev.buijs.stratastax.persistence.reactive.app.currentTransaction
import org.jooq.Query

/**
 * A [QueryExecutor] that runs every query inside the transaction the calling coroutine is in (one
 * started by [owner]'s [VertxTransactionalExecutor]), or on [fallback] when there is none.
 *
 * This is the executor the access classes get injected: a read, or any statement an access class
 * issues outside its own `withTransaction` block, then sees and takes part in the caller's
 * [dev.buijs.stratastax.persistence.core.api.UnitOfWork.transaction] instead of silently running on
 * a different connection in autocommit mode.
 */
class AmbientQueryExecutor(
    private val owner: Any,
    private val fallback: QueryExecutor,
) : QueryExecutor {

    private suspend fun current(): QueryExecutor = currentTransaction(owner)?.executor ?: fallback

    override suspend fun executeAndGetAll(query: Query): List<RowContext> =
        current().executeAndGetAll(query)

    override suspend fun executeAndGetFirst(query: Query): RowContext? =
        current().executeAndGetFirst(query)

    override suspend fun exists(query: Query): Boolean = current().exists(query)

    override suspend fun executeAndGetCount(query: Query): Long =
        current().executeAndGetCount(query)

    override suspend fun executeUpdate(query: Query): Int = current().executeUpdate(query)

    override suspend fun executeReturning(query: Query): RowContext? =
        current().executeReturning(query)
}
