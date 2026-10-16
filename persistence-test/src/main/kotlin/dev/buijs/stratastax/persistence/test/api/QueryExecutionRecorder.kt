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
package dev.buijs.stratastax.persistence.test.api

import dev.buijs.stratastax.persistence.core.api.QueryExecution
import dev.buijs.stratastax.persistence.core.spi.QueryExecutionListener

/**
 * Wire one instance of this into the same `QueryExecutor` (`VertxQueryExecutor(pool, listeners =
 * listOf(recorder))`) a test's generated repositories actually run through, then hand it to a
 * [PersistenceTestHarness] - every query that executor runs, production code path included, is
 * appended here in order.
 */
class QueryExecutionRecorder : QueryExecutionListener {

    private val executions = mutableListOf<QueryExecution>()

    override suspend fun executed(execution: QueryExecution) {
        executions += execution
    }

    fun executions(): List<QueryExecution> = executions.toList()
}
