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

import dev.buijs.stratastax.persistence.core.spi.QueryOperation
import kotlin.time.Duration

/** Fails when the block threw, listing the statements that ran before it did. */
public fun <T> TestExecution<T>.assertSuccessful(): TestExecution<T> {
    val thrown = error ?: return this
    throw AssertionError(
        "Persistence execution failed after ${dataQueries.size} ${queries(
            dataQueries.size,
        )}: ${thrown.message}\n" +
            describeStatements(queries),
        thrown,
    )
}

/** Fails unless exactly [expected] data statements ran (transaction control is not counted). */
public fun <T> TestExecution<T>.assertQueryCount(expected: Int): TestExecution<T> {
    if (dataQueries.size != expected) {
        throw AssertionError(
            "Expected $expected ${queries(expected)} but ${dataQueries.size} ran:\n" +
                describeStatements(queries),
        )
    }

    return this
}

/**
 * Fails when one SQL text ran [threshold] or more times: the signature of an N+1 problem - a
 * relation loaded per row instead of in one query.
 */
public fun <T> TestExecution<T>.assertNoNPlusOne(threshold: Int = 3): TestExecution<T> {
    val repeated =
        dataQueries.groupBy { it.execution.query.sql }.filterValues {
            it.size >=
                threshold
        }

    if (repeated.isNotEmpty()) {
        val report =
            repeated.entries.joinToString(
                "\n\n",
            ) { (sql, occurrences) -> "Repeated query (${occurrences.size}x):\n    $sql" }

        throw AssertionError(
            "Potential N+1 query detected (${dataQueries.size} queries in total)\n\n$report\n\n" +
                "Load the relation for all rows at once (a join, an array subquery or a batched IN).",
        )
    }

    return this
}

/** Fails when a plan contains a sequential scan. Needs a [QueryPlanAnalyzer]. */
public fun <T> TestExecution<T>.assertNoSequentialScans(): TestExecution<T> {
    val offenders = plans().flatMap { (query, plan) -> plan.sequentialScans().map { query to it } }

    if (offenders.isNotEmpty()) {
        val report =
            offenders.joinToString("\n\n") { (query, node) ->
                "Query:\n    ${query.execution.query.sql}\n" +
                    "Sequential scan on: ${node.relationName ?: "?"} " +
                    "(rows removed by filter: ${node.rowsRemovedByFilter ?: 0})"
            }

        throw AssertionError("Sequential scan detected\n\n$report")
    }

    return this
}

/** Fails unless some plan used index [name]. Needs a [QueryPlanAnalyzer]. */
public fun <T> TestExecution<T>.assertUsesIndex(name: String): TestExecution<T> {
    val used = plans().flatMap { (_, plan) -> plan.indexesUsed() }.toSet()
    if (name !in used) {
        throw AssertionError(
            "Expected index '$name' to be used, but it wasn't. Indexes used: ${used.ifEmpty {
                setOf(
                    "none",
                )
            }.joinToString()}",
        )
    }

    return this
}

/** Fails when the plans read more than [max] rows in total. Needs a [QueryPlanAnalyzer]. */
public fun <T> TestExecution<T>.assertMaxRowsRead(max: Long): TestExecution<T> {
    val plans = plans()
    val rowsRead = plans.sumOf { (_, plan) -> plan.rowsRead() }

    if (rowsRead > max) {
        throw AssertionError(
            "Expected at most $max rows read, but $rowsRead were:\n" +
                plans.joinToString(
                    "\n",
                ) { (query, plan) -> "  ${plan.rowsRead()} - ${query.execution.query.sql}" },
        )
    }

    return this
}

/** Fails when the plans read more than [max] shared buffer blocks. Needs a [QueryPlanAnalyzer]. */
public fun <T> TestExecution<T>.assertMaxBufferReads(max: Long): TestExecution<T> {
    val buffersRead = plans().sumOf { (_, plan) -> plan.bufferReads() }

    if (buffersRead > max) {
        throw AssertionError(
            "Expected at most $max shared buffer blocks read, but $buffersRead were.",
        )
    }

    return this
}

/** Fails when a statement took [max] or longer to execute. Machine-dependent: use sparingly. */
public fun <T> TestExecution<T>.assertExecutionTimeBelow(max: Duration): TestExecution<T> {
    val offenders = plans().filter { (_, plan) -> plan.executionTime >= max }

    if (offenders.isNotEmpty()) {
        throw AssertionError(
            "Expected execution time below $max:\n" +
                offenders.joinToString(
                    "\n",
                ) { (query, plan) -> "  ${plan.executionTime} - ${query.execution.query.sql}" },
        )
    }

    return this
}

/** Checks the SQL of the data statements, see [SqlExpectationBuilder]. */
public fun <T> TestExecution<T>.assertQuery(
    block: SqlExpectationBuilder.() -> Unit,
): TestExecution<T> {
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

/** Fails when a statement failed, listing every statement that ran. */
public fun <T> TestExecution<T>.assertNoFailedQueries(): TestExecution<T> {
    if (queries.any { it.execution.failed }) {
        throw AssertionError("Expected no failed statements:\n" + describeStatements(queries))
    }

    return this
}

/** Fails unless at least one transaction ran and every transaction ended with `COMMIT`. */
public fun <T> TestExecution<T>.assertCommitted(): TestExecution<T> =
    assertEveryTransactionEndsWith(QueryOperation.COMMIT)

/** Fails unless at least one transaction ran and every transaction ended with `ROLLBACK`. */
public fun <T> TestExecution<T>.assertRolledBack(): TestExecution<T> =
    assertEveryTransactionEndsWith(QueryOperation.ROLLBACK)

/** Fails unless every write (`INSERT`/`UPDATE`/`DELETE`) ran in one and the same transaction. */
public fun <T> TestExecution<T>.assertSingleTransaction(): TestExecution<T> {
    val writes = queries.filter { it.execution.operation in WRITE_OPERATIONS }

    val transactionIds = writes.map { it.execution.transactionId }.distinct()
    if (writes.isEmpty() || transactionIds.size != 1 || transactionIds.single() == null) {
        throw AssertionError(
            "Expected every write to run in one transaction, but found " +
                "${transactionIds.size} distinct " +
                "transaction(s) across ${writes.size} write(s) (autocommit shown as '-'):\n" +
                describeStatements(queries),
        )
    }

    return this
}

/** The statements, one per line: position, transaction, operation, SQL and failure. */
public fun describeStatements(statements: List<ObservedQuery>): String =
    statements
        .mapIndexed { index, observed ->
            val execution = observed.execution
            val transaction = execution.transactionId?.take(8) ?: "-"
            val failure = execution.error?.let { "  ✗ ${it.message}" } ?: ""
            "  ${index + 1}. [tx $transaction] ${execution.operation}: ${execution.query.sql}$failure"
        }.joinToString("\n")

private val WRITE_OPERATIONS =
    setOf(QueryOperation.INSERT, QueryOperation.UPDATE, QueryOperation.DELETE)

private fun queries(count: Int): String = if (count == 1) "query" else "queries"

private fun <T> TestExecution<T>.assertEveryTransactionEndsWith(
    expected: QueryOperation,
): TestExecution<T> {
    val transactions =
        queries
            .filter { it.execution.transactionId != null }
            .groupBy { it.execution.transactionId }

    val endings = transactions.values.map { it.last().execution.operation }

    if (transactions.isEmpty() || endings.any { it != expected }) {
        val actual =
            if (transactions.isEmpty()) {
                "no transaction ran"
            } else {
                "they ended with ${endings.joinToString()}"
            }

        throw AssertionError(
            "Expected every transaction to end with $expected, but $actual:\n" +
                describeStatements(queries),
        )
    }

    return this
}

/** Every data statement with its plan; fails when the harness has no [QueryPlanAnalyzer]. */
private fun <T> TestExecution<T>.plans(): List<Pair<ObservedQuery, QueryPlan>> =
    dataQueries.map { query ->
        val plan =
            query.plan
                ?: throw AssertionError(
                    "No query plan for '${query.execution.query.sql}' - " +
                        "build the PersistenceTestHarness " +
                        "with a QueryPlanAnalyzer to use plan assertions",
                )
        query to plan
    }
