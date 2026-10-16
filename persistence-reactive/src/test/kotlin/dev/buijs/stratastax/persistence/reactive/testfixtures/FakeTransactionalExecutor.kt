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
package dev.buijs.stratastax.persistence.reactive.testfixtures

import dev.buijs.stratastax.persistence.core.api.TransactionOptions
import dev.buijs.stratastax.persistence.reactive.api.QueryExecutor
import dev.buijs.stratastax.persistence.reactive.api.TransactionalExecutor

/**
 * A no-database [TransactionalExecutor] test double - just runs [block] against the given
 * [executor] directly, no real transaction semantics (there's no real connection to begin one on).
 * What matters for these tests is *that* the root write and every junction write go through the
 * same executor instance, not genuine commit/rollback behavior.
 */
internal class FakeTransactionalExecutor(
    private val executor: QueryExecutor,
) : TransactionalExecutor {

    var transactionCount = 0
        private set

    override suspend fun <T> withTransaction(
        options: TransactionOptions,
        block: suspend (QueryExecutor) -> T,
    ): T {
        transactionCount++
        return block(executor)
    }
}
