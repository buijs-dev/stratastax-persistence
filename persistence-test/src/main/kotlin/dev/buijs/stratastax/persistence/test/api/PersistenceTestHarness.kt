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

/**
 * One query [PersistenceTestHarness.execute] observed [block] cause - [plan] is `null` unless the
 * harness was built with a [QueryPlanAnalyzer].
 */
data class ObservedQuery(
    val execution: QueryExecution,
    val plan: QueryPlan? = null,
)

/**
 * The outcome of one [PersistenceTestHarness.execute] call: [block]'s own result (or the [error] it
 * threw) plus every statement it caused, in the order they ran - [queries] includes transaction
 * control (`BEGIN`, `SAVEPOINT`, `COMMIT`, ...) and failed statements.
 *
 * The data assertions (`assertQueryCount`, `assertNoNPlusOne`, plan checks, ...) look at
 * [dataQueries] only, so a query budget does not change when a call starts running inside a
 * transaction; the transaction assertions (`assertCommitted`, `assertRolledBack`,
 * `assertSingleTransaction`) look at [queries].
 */
data class TestExecution<T>(
    val result: T?,
    val error: Throwable?,
    val queries: List<ObservedQuery>,
) {

    /** [queries] without the transaction-control statements. */
    val dataQueries: List<ObservedQuery>
        get() = queries.filterNot { it.execution.operation.isTransactionControl }

    /** Only the transaction-control statements of [queries]. */
    val transactionStatements: List<ObservedQuery>
        get() = queries.filter { it.execution.operation.isTransactionControl }
}

/**
 * Runs [block] and reports exactly the queries it caused - [recorder] is shared across a whole test
 * (or test class), so [execute] snapshots its size before and after [block] rather than resetting
 * it, letting a test call [execute] more than once and still get a query list scoped to each call.
 *
 * [analyzer], when given, runs `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` for every observed data
 * query that succeeded (transaction-control and failed statements have no plan) after [block]
 * completes - never during, so [block]'s own timing/row-count observations are unaffected by the
 * extra analysis pass - filling in [ObservedQuery.plan] for the plan-based assertions
 * (`assertNoSequentialScans`, `assertUsesIndex`, ...). Omit it (the default) when a test only needs
 * the SQL-rendering/count-based assertions - no need to pay for `EXPLAIN ANALYZE` re-running every
 * query if nothing consults the plan.
 */
class PersistenceTestHarness(
    private val recorder: QueryExecutionRecorder,
    private val analyzer: QueryPlanAnalyzer? = null,
) {

    suspend fun <T> execute(block: suspend () -> T): TestExecution<T> {
        val before = recorder.executions().size
        return runCatching { block() }
            .fold(
                onSuccess = { TestExecution(it, null, observedSince(before)) },
                onFailure = { TestExecution(null, it, observedSince(before)) },
            )
    }

    private suspend fun observedSince(index: Int): List<ObservedQuery> =
        recorder.executions().drop(index).map { execution ->
            val explainable = !execution.operation.isTransactionControl && !execution.failed
            ObservedQuery(execution, if (explainable) analyzer?.analyze(execution.query) else null)
        }
}
