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

import kotlin.time.Duration

/** One check of a [ScenarioReport]: passed or not, grouped under SQL, PLAN or PERFORMANCE. */
public data class ExpectationOutcome(
    val group: String,
    val label: String,
    val passed: Boolean,
    val detail: String? = null,
)

/** Every check of one [scenario], rendered as one readable report. */
public class ScenarioReport(
    public val name: String,
    public val outcomes: List<ExpectationOutcome>,
) {

    /** Whether every check passed. */
    public val passed: Boolean
        get() = outcomes.all { it.passed }

    /** The report: `✓`/`✗` per check, grouped under SQL/PLAN/PERFORMANCE headings. */
    public fun render(): String =
        buildString {
            appendLine("${if (passed) "✓" else "✗"} $name")
            appendLine()
            outcomes.groupBy { it.group }.forEach { (group, groupOutcomes) ->
                appendLine(group)
                groupOutcomes.forEach { outcome ->
                    appendLine("  ${if (outcome.passed) "✓" else "✗"} ${outcome.label}")
                    if (!outcome.passed && outcome.detail != null) {
                        appendLine("    ${outcome.detail}")
                    }
                }

                appendLine()
            }
        }
}

/** Checks on the SQL text of the data statements, see [SqlChecks]. */
public class SqlExpectationBuilder internal constructor(
    private val statements: List<String>,
) {

    internal val outcomes = mutableListOf<ExpectationOutcome>()

    /** A statement reads from or joins [table]. */
    public fun usesTable(table: String) {
        outcomes +=
            ExpectationOutcome("SQL", "table $table", SqlChecks.usesTable(statements, table))
    }

    /** A statement mentions [column]. */
    public fun usesColumn(column: String) {
        outcomes +=
            ExpectationOutcome("SQL", "column $column", SqlChecks.usesColumn(statements, column))
    }

    /** A statement filters on [column]. */
    public fun usesPredicate(column: String) {
        outcomes +=
            ExpectationOutcome(
                "SQL",
                "predicate $column",
                SqlChecks.usesPredicate(statements, column),
            )
    }

    /** No statement contains [text]. */
    public fun doesNotContain(text: String) {
        outcomes +=
            ExpectationOutcome(
                "SQL",
                "does not contain \"$text\"",
                SqlChecks.doesNotContain(statements, text),
            )
    }
}

/** Checks on the plans of the data statements. */
public class PlanExpectationBuilder internal constructor(
    private val plans: List<QueryPlan>,
) {

    internal val outcomes = mutableListOf<ExpectationOutcome>()

    /** At most this many rows read in total, checked when the block ends. */
    public var maxRowsRead: Long? = null

    /** At most this many shared buffer blocks read in total, checked when the block ends. */
    public var maxBufferReads: Long? = null

    /** No plan contains a sequential scan. */
    public fun noSequentialScans() {
        val offenders = plans.flatMap { it.sequentialScans() }

        outcomes +=
            ExpectationOutcome(
                "PLAN",
                "no sequential scans",
                offenders.isEmpty(),
                offenders
                    .joinToString {
                        it.relationName
                            ?: "?"
                    }.ifEmpty { null },
            )
    }

    /** Some plan used index [name]. */
    public fun usesIndex(name: String) {
        outcomes +=
            ExpectationOutcome("PLAN", "uses index $name", plans.any { name in it.indexesUsed() })
    }

    internal fun finish() {
        maxRowsRead?.let { max ->
            val actual = plans.sumOf { it.rowsRead() }

            outcomes +=
                ExpectationOutcome("PLAN", "rows read <= $max", actual <= max, "actual: $actual")
        }

        maxBufferReads?.let { max ->
            val actual = plans.sumOf { it.bufferReads() }

            outcomes +=
                ExpectationOutcome("PLAN", "buffers read <= $max", actual <= max, "actual: $actual")
        }
    }
}

/** Checks on the execution time of the data statements. Machine-dependent: use sparingly. */
public class PerformanceExpectationBuilder internal constructor(
    private val plans: List<QueryPlan>,
) {

    internal val outcomes = mutableListOf<ExpectationOutcome>()

    /** Every statement executes in at most this long, checked when the block ends. */
    public var maxExecutionTime: Duration? = null

    internal fun finish() {
        maxExecutionTime?.let { max ->
            plans.forEach { plan ->
                outcomes +=
                    ExpectationOutcome(
                        "PERFORMANCE",
                        "execution time ${plan.executionTime} <= $max",
                        plan.executionTime <= max,
                    )
            }
        }
    }
}

/** The expectations of a scenario, see [scenario]. */
public class ExpectationBuilder internal constructor(
    private val execution: TestExecution<*>,
) {

    internal val outcomes = mutableListOf<ExpectationOutcome>()

    /** Exactly this many data statements ran, checked when the block ends. */
    public var queries: Int? = null

    /** Checks on the SQL text. */
    public fun sql(block: SqlExpectationBuilder.() -> Unit) {
        val builder = SqlExpectationBuilder(execution.dataQueries.map { it.execution.query.sql })
        builder.block()
        outcomes += builder.outcomes
    }

    /** Checks on the plans; needs a [QueryPlanAnalyzer]. */
    public fun plan(block: PlanExpectationBuilder.() -> Unit) {
        val builder = PlanExpectationBuilder(requirePlans(execution.dataQueries))
        builder.block()
        builder.finish()
        outcomes += builder.outcomes
    }

    /** Checks on the execution times; needs a [QueryPlanAnalyzer]. */
    public fun performance(block: PerformanceExpectationBuilder.() -> Unit) {
        val builder = PerformanceExpectationBuilder(requirePlans(execution.dataQueries))
        builder.block()
        builder.finish()
        outcomes += builder.outcomes
    }

    internal fun finish() {
        queries?.let { expected ->
            val actual = execution.dataQueries.size
            outcomes +=
                ExpectationOutcome(
                    "SQL",
                    "$expected ${if (expected == 1) "query" else "queries"} executed",
                    actual == expected,
                    "actual: $actual",
                )
        }
    }
}

/** One scenario: run a block with [execute], then check what it did with [expect]. */
public class ScenarioBuilder internal constructor(
    private val harness: PersistenceTestHarness,
) {

    private var execution: TestExecution<*>? = null
    private val outcomes = mutableListOf<ExpectationOutcome>()

    /** Runs [block], recording the statements it causes. */
    public suspend fun execute(block: suspend () -> Any?) {
        execution = harness.execute(block)
    }

    /** Checks what the block of [execute] did. */
    public fun expect(block: ExpectationBuilder.() -> Unit) {
        val current = checkNotNull(execution) { "call execute { } before expect { } in a scenario" }

        val builder = ExpectationBuilder(current)
        builder.block()
        builder.finish()
        outcomes += builder.outcomes
    }

    internal fun buildReport(name: String) = ScenarioReport(name, outcomes)
}

/**
 * Runs a named scenario and returns its report, e.g.
 *
 * ```
 * harness.scenario("search playsets") {
 *     execute { playsets.findAll(search) }
 *     expect {
 *         queries = 1
 *         sql { usesTable("playset") }
 *         plan { noSequentialScans() }
 *     }
 * }
 * ```
 *
 * Every check runs, also after one failed; when any failed this throws an [AssertionError] holding
 * the full rendered report.
 */
public suspend fun PersistenceTestHarness.scenario(
    name: String,
    block: suspend ScenarioBuilder.() -> Unit,
): ScenarioReport {
    val builder = ScenarioBuilder(this)
    builder.block()
    val report = builder.buildReport(name)
    if (!report.passed) throw AssertionError(report.render())
    return report
}

private fun requirePlans(queries: List<ObservedQuery>): List<QueryPlan> {
    val plans = queries.mapNotNull { it.plan }

    check(plans.size == queries.size) {
        "plan { } and performance { } need a plan for every statement - build the PersistenceTestHarness with a QueryPlanAnalyzer"
    }

    return plans
}
