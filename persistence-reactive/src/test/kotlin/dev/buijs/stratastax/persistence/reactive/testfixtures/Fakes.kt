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
import dev.buijs.stratastax.persistence.core.spi.RowContext
import dev.buijs.stratastax.persistence.reactive.spi.QueryExecutor
import dev.buijs.stratastax.persistence.reactive.spi.TransactionalExecutor

/**
 * A [QueryExecutor] without database: records every statement and answers with [sqlAnswer], which
 * by default returns nothing. For tests about the control flow around the statements.
 */
internal class FakeQueryExecutor(
    private val sqlAnswer: (String, List<Any?>) -> List<RowContext> = { _, _ -> emptyList() },
) : QueryExecutor {

    /** Every SQL statement run, with its parameters; a batch adds one entry per parameter set. */
    val statements = mutableListOf<Pair<String, List<Any?>>>()

    private fun run(
        sql: String,
        params: List<Any?>,
    ): List<RowContext> {
        statements += sql to params
        return sqlAnswer(sql, params)
    }

    override suspend fun query(
        sql: String,
        params: List<Any?>,
    ): List<RowContext> = run(sql, params)

    override suspend fun queryFirst(
        sql: String,
        params: List<Any?>,
    ): RowContext? = run(sql, params).firstOrNull()

    override suspend fun execute(
        sql: String,
        params: List<Any?>,
    ): Int = run(sql, params).size

    override suspend fun batch(
        sql: String,
        batch: List<List<Any?>>,
    ): List<List<RowContext>> = batch.map { run(sql, it) }
}

/** A [TransactionalExecutor] that runs the block on [executor] directly, counting transactions. */
internal class FakeTransactionalExecutor(
    private val executor: QueryExecutor,
) : TransactionalExecutor {

    var transactions = 0
        private set

    private var depth = 0

    override suspend fun <T> withTransaction(
        options: TransactionOptions,
        block: suspend (QueryExecutor) -> T,
    ): T {
        transactions++
        depth++
        try {
            return block(executor)
        } finally {
            depth--
        }
    }

    override suspend fun isInTransaction(): Boolean = depth > 0
}
