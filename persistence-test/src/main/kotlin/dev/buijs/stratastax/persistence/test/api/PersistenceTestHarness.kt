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

import dev.buijs.stratastax.persistence.core.spi.QueryExecution
import dev.buijs.stratastax.persistence.core.spi.runCatchingNonCancellation

/**
 * One statement [PersistenceTestHarness.execute] observed; [plan] is `null` unless the harness has
 * a [QueryPlanAnalyzer] (and for transaction-control or failed statements, which have no plan).
 */
public data class ObservedQuery(
    val execution: QueryExecution,
    val plan: QueryPlan? = null,
)

/**
 * The outcome of one [PersistenceTestHarness.execute] call: the block's [result] (or the [error]
 * it threw) and every statement it caused, in order - [queries] includes transaction control
 * (`BEGIN`, `SAVEPOINT`, `COMMIT`, ...) and failed statements.
 *
 * The data assertions (`assertQueryCount`, `assertNoNPlusOne`, the plan checks) look at
 * [dataQueries] only, so a query budget does not change when a call starts running inside a
 * transaction; the transaction assertions (`assertCommitted`, `assertRolledBack`,
 * `assertSingleTransaction`) look at [queries].
 */
public data class TestExecution<T>(
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
 * Runs a block and reports exactly the statements it caused, as recorded by [recorder] - wire the
 * same recorder into the executors the code under test uses (see [QueryExecutionRecorder]).
 *
 * The recorder may be shared by a whole test class: [execute] only reports what was recorded while
 * its block ran.
 *
 * With an [analyzer], every successful data statement is also run through `EXPLAIN (ANALYZE,
 * BUFFERS, FORMAT JSON)` after the block completed (so the block's own timing is unaffected),
 * which the plan assertions (`assertNoSequentialScans`, `assertUsesIndex`, ...) need.
 */
public class PersistenceTestHarness(
    private val recorder: QueryExecutionRecorder,
    private val analyzer: QueryPlanAnalyzer? = null,
) {

    /**
     * Runs [block] and returns its outcome with the statements it caused. An exception thrown by
     * [block] is captured in [TestExecution.error], not rethrown; an [Error] (such as a failed
     * assertion) and a cancellation are rethrown.
     */
    public suspend fun <T> execute(block: suspend () -> T): TestExecution<T> {
        val before = recorder.executions().size
        val outcome = runCatchingNonCancellation { block() }

        return TestExecution(outcome.getOrNull(), outcome.exceptionOrNull(), observedSince(before))
    }

    private suspend fun observedSince(index: Int): List<ObservedQuery> =
        recorder.executions().drop(index).map { execution ->
            val explainable = !execution.operation.isTransactionControl && !execution.failed
            ObservedQuery(execution, if (explainable) analyzer?.analyze(execution.query) else null)
        }
}
