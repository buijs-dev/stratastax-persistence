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

import dev.buijs.stratastax.persistence.core.api.QueryOperation
import kotlin.time.Duration

/**
 * Fails if [TestExecution.execute]'s block threw. Reports the captured error and how many queries
 * ran before it did, so a broken query is easy to tell apart from a broken assertion further down
 * the chain.
 */
fun <T> TestExecution<T>.assertSuccessful(): TestExecution<T> {
    val thrown = error
    if (thrown != null) {
        throw AssertionError(
            "Persistence execution failed after ${dataQueries.size} " +
                "quer${if (dataQueries.size == 1) "y" else "ies"}: ${thrown.message}\n" +
                describeStatements(queries),
            thrown,
        )
    }

    return this
}

/**
 * Fails unless exactly [expected] data queries ran (transaction-control statements don't count),
 * listing every statement that ran.
 */
fun <T> TestExecution<T>.assertQueryCount(expected: Int): TestExecution<T> {
    if (dataQueries.size != expected) {
        throw AssertionError(
            "Expected $expected quer${if (expected == 1) "y" else "ies"} " +
                "but ${dataQueries.size} ran:\n" +
                describeStatements(queries),
        )
    }

    return this
}

/**
 * Fails if any distinct SQL statement ran [threshold] times or more - the classic sign of a
 * one-to-many relation being loaded query-per-parent-row instead of via a join/batch fetch.
 */
fun <T> TestExecution<T>.assertNoNPlusOne(threshold: Int = 3): TestExecution<T> {
    val repeated =
        dataQueries.groupBy { it.execution.query.sql }.filterValues { it.size >= threshold }

    if (repeated.isNotEmpty()) {
        val report =
            repeated.entries.joinToString("\n\n") { (sql, occurrences) ->
                "Repeated query:\n    $sql\n\nOccurrences:\n    ${occurrences.size}"
            }

        throw AssertionError(
            "Potential N+1 query detected\n\n" +
                "Total queries: ${dataQueries.size}\n\n$report\n\n" +
                "Likely cause:\n    One-to-many relation loaded individually.\n\n" +
                "Suggestion:\n    Consider MULTISET / JOIN / batch loading.",
        )
    }

    return this
}

/**
 * Every [ObservedQuery.plan] a plan-based assertion needs - fails loudly (rather than silently
 * passing on no data) if the harness wasn't built with a [QueryPlanAnalyzer], since that's a
 * test-setup mistake, not "no sequential scans found".
 */
private fun <T> TestExecution<T>.plans(): List<Pair<ObservedQuery, QueryPlan>> =
    dataQueries.map { query ->
        val plan =
            query.plan
                ?: throw AssertionError(
                    "No query plan available for '${query.execution.query.sql}' - " +
                        "build PersistenceTestHarness with a QueryPlanAnalyzer to use plan-based assertions.",
                )
        query to plan
    }

/** Fails if any observed query's plan contains a sequential scan anywhere in its tree. */
fun <T> TestExecution<T>.assertNoSequentialScans(): TestExecution<T> {
    val offenders =
        plans().flatMap { (query, plan) ->
            plan.flatten().filter { it.isSequentialScan() }.map { query to it }
        }

    if (offenders.isNotEmpty()) {
        val report =
            offenders.joinToString("\n\n") { (query, node) ->
                "Query:\n    ${query.execution.query.sql}\n\n" +
                    "Sequential scan on:\n    ${node.relationName ?: "?"}\n\n" +
                    "Rows removed by filter:\n    ${node.rowsRemovedByFilter ?: 0}"
            }

        throw AssertionError("Sequential scan detected\n\n$report")
    }

    return this
}

/** Fails unless some observed query's plan actually used index [name]. */
fun <T> TestExecution<T>.assertUsesIndex(name: String): TestExecution<T> {
    val used = plans().flatMap { (_, plan) -> plan.flatten() }.any { it.indexName == name }

    if (!used) {
        val seen =
            plans().flatMap { (_, plan) -> plan.flatten() }.mapNotNull { it.indexName }.distinct()
        throw AssertionError(
            "Expected index '$name' to be used, but it wasn't.\n" +
                "Indexes actually used: ${if (seen.isEmpty()) "none" else seen.joinToString(", ")}",
        )
    }

    return this
}

/**
 * Fails if the total rows read across every observed query's leaf (scan) nodes exceeds [max] - a
 * hardware-independent stand-in for "how much data did Postgres have to touch," unlike execution
 * time.
 */
fun <T> TestExecution<T>.assertMaxRowsRead(max: Long): TestExecution<T> {
    val rowsRead =
        plans().sumOf { (_, plan) ->
            plan
                .flatten()
                .filter { it.children.isEmpty() }
                .sumOf { (it.actualRows ?: 0L) * (it.actualLoops ?: 1L) }
        }

    if (rowsRead > max) {
        throw AssertionError(
            "Expected at most $max rows read, but $rowsRead were.\n" +
                plans().joinToString("\n") { (query, _) -> "  ${query.execution.query.sql}" },
        )
    }

    return this
}

/**
 * Fails if the total shared buffer blocks read (hit + read from disk) across every observed query's
 * plan exceeds [max].
 */
fun <T> TestExecution<T>.assertMaxBufferReads(max: Long): TestExecution<T> {
    val buffersRead =
        plans().sumOf { (_, plan) ->
            plan.flatten().sumOf { (it.sharedHitBlocks ?: 0L) + (it.sharedReadBlocks ?: 0L) }
        }

    if (buffersRead > max) {
        throw AssertionError(
            "Expected at most $max shared buffer blocks read, but $buffersRead were.",
        )
    }

    return this
}

/**
 * Fails unless every check in [block] (`usesTable`, `usesColumn`, `usesPredicate`,
 * `doesNotContain`) passes against the rendered SQL of every observed query - semantic checks, not
 * exact-string matching (see [SqlChecks]'s kdoc for why).
 */
fun <T> TestExecution<T>.assertQuery(block: SqlExpectationBuilder.() -> Unit): TestExecution<T> {
    val builder = SqlExpectationBuilder(dataQueries.map { it.execution.query.sql })
    builder.block()

    val failed = builder.outcomes.filterNot { it.passed }

    if (failed.isNotEmpty()) {
        throw AssertionError(
            "SQL assertion failed:\n" + failed.joinToString("\n") { "  ✗ ${it.label}" },
        )
    }

    return this
}

/**
 * Fails if any observed query's own execution time (as reported by `EXPLAIN ANALYZE`, not
 * wall-clock time around the whole [TestExecution.execute] block) exceeds [max].
 * CI-machine-dependent by nature - prefer [assertMaxRowsRead]/[assertMaxBufferReads] where the same
 * invariant can be expressed in hardware-independent terms.
 */
fun <T> TestExecution<T>.assertExecutionTimeBelow(max: Duration): TestExecution<T> {
    val offenders = plans().filter { (_, plan) -> plan.executionTime > max }

    if (offenders.isNotEmpty()) {
        throw AssertionError(
            "Expected execution time below $max:\n" +
                offenders.joinToString("\n") { (query, plan) ->
                    "  ${plan.executionTime} - ${query.execution.query.sql}"
                },
        )
    }

    return this
}

/** Fails if any statement failed, listing every statement that ran. */
fun <T> TestExecution<T>.assertNoFailedQueries(): TestExecution<T> {
    if (queries.any { it.execution.failed }) {
        throw AssertionError("Expected no failed statements:\n" + describeStatements(queries))
    }

    return this
}

/** Fails unless at least one transaction ran and every transaction ended with `COMMIT`. */
fun <T> TestExecution<T>.assertCommitted(): TestExecution<T> =
    assertEveryTransactionEndsWith(QueryOperation.COMMIT)

/** Fails unless at least one transaction ran and every transaction ended with `ROLLBACK`. */
fun <T> TestExecution<T>.assertRolledBack(): TestExecution<T> =
    assertEveryTransactionEndsWith(QueryOperation.ROLLBACK)

/**
 * Fails unless every data write (`INSERT`/`UPDATE`/`DELETE`) ran inside one and the same
 * transaction - i.e. the writes are atomic, none of them ran in autocommit mode or in a transaction
 * of its own.
 */
fun <T> TestExecution<T>.assertSingleTransaction(): TestExecution<T> {
    val writes = queries.filter { it.execution.operation in WRITE_OPERATIONS }

    val transactionIds = writes.map { it.execution.transactionId }.distinct()
    if (writes.isEmpty() || transactionIds.size != 1 || transactionIds.single() == null) {
        throw AssertionError(
            "Expected every write to run in one transaction, but found " +
                "${transactionIds.size} distinct transaction(s) across ${writes.size} write(s) " +
                "(autocommit shown as '-'):\n" +
                describeStatements(queries),
        )
    }

    return this
}

private val WRITE_OPERATIONS =
    setOf(QueryOperation.INSERT, QueryOperation.UPDATE, QueryOperation.DELETE)

private fun <T> TestExecution<T>.assertEveryTransactionEndsWith(
    expected: QueryOperation,
): TestExecution<T> {
    val transactions =
        queries.filter { it.execution.transactionId != null }.groupBy { it.execution.transactionId }

    val endings = transactions.values.map { statements -> statements.last().execution.operation }

    if (transactions.isEmpty() || endings.any { it != expected }) {
        throw AssertionError(
            "Expected every transaction to end with $expected, but " +
                (
                    if (transactions.isEmpty()) {
                        "no transaction ran"
                    } else {
                        "they ended with ${endings.joinToString()}"
                    }
                ) +
                ":\n" +
                describeStatements(queries),
        )
    }

    return this
}

/**
 * One line per statement - position, the (shortened) transaction id or `-` for autocommit, the
 * operation, the SQL and, for a failed statement, its error - so a failure message shows the whole
 * transaction flow at a glance.
 */
fun describeStatements(statements: List<ObservedQuery>): String =
    statements
        .mapIndexed { index, observed ->
            val execution = observed.execution
            val transaction = execution.transactionId?.take(8) ?: "-"
            val failure = execution.error?.let { "  ✗ ${it.message}" } ?: ""
            "  ${index + 1}. [tx $transaction] ${execution.operation}: " +
                "${execution.query.sql}$failure"
        }.joinToString("\n")
