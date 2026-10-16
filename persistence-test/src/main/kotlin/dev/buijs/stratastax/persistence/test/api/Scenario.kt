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

/** One line of a [ScenarioReport] - a single named check, passed or not. */
data class ExpectationOutcome(
    val group: String,
    val label: String,
    val passed: Boolean,
    val detail: String? = null,
)

/**
 * The full result of one [PersistenceTestHarness.scenario] - every expectation that ran, grouped
 * and rendered the way the doc's own examples show (`✓`/`✗` per line, grouped under
 * SQL/PLAN/PERFORMANCE headings).
 */
class ScenarioReport(
    val name: String,
    val outcomes: List<ExpectationOutcome>,
) {

    val passed: Boolean
        get() = outcomes.all { it.passed }

    fun render(): String =
        buildString {
            appendLine("${if (passed) "✓" else "✗"} $name")
            appendLine()
            outcomes
                .groupBy { it.group }
                .forEach { (group, groupOutcomes) ->
                    appendLine(group)
                    groupOutcomes.forEach { outcome ->
                        val mark = if (outcome.passed) "✓" else "✗"
                        appendLine("  $mark ${outcome.label}")
                        if (!outcome.passed && outcome.detail != null) {
                            appendLine("    ${outcome.detail}")
                        }
                    }

                    appendLine()
                }
        }
}

private fun requirePlans(queries: List<ObservedQuery>): List<QueryPlan> {
    val plans = queries.mapNotNull { it.plan }

    check(plans.size == queries.size) {
        "plan {} / performance {} need every observed query to have a plan - " +
            "build the PersistenceTestHarness with a QueryPlanAnalyzer."
    }

    return plans
}

class SqlExpectationBuilder internal constructor(
    private val sqlStatements: List<String>,
) {

    internal val outcomes = mutableListOf<ExpectationOutcome>()

    fun usesTable(table: String) {
        outcomes +=
            ExpectationOutcome("SQL", "table $table", SqlChecks.usesTable(sqlStatements, table))
    }

    fun usesColumn(column: String) {
        outcomes +=
            ExpectationOutcome("SQL", "column $column", SqlChecks.usesColumn(sqlStatements, column))
    }

    fun usesPredicate(column: String) {
        outcomes +=
            ExpectationOutcome(
                "SQL",
                "predicate $column",
                SqlChecks.usesPredicate(sqlStatements, column),
            )
    }

    fun doesNotContain(text: String) {
        outcomes +=
            ExpectationOutcome(
                "SQL",
                "does not contain \"$text\"",
                SqlChecks.doesNotContain(sqlStatements, text),
            )
    }
}

class PlanExpectationBuilder internal constructor(
    private val plans: List<QueryPlan>,
) {

    internal val outcomes = mutableListOf<ExpectationOutcome>()

    /** Checked once the `plan { }` block finishes - see [maxBufferReads] too. */
    var maxRowsRead: Long? = null

    /** Checked once the `plan { }` block finishes - see [maxRowsRead] too. */
    var maxBufferReads: Long? = null

    fun noSequentialScans() {
        val offenders = plans.flatMap { it.flatten() }.filter { it.isSequentialScan() }

        outcomes +=
            ExpectationOutcome(
                "PLAN",
                "no sequential scans",
                offenders.isEmpty(),
                offenders.joinToString { it.relationName ?: "?" }.ifEmpty { null },
            )
    }

    fun usesIndex(name: String) {
        val used = plans.flatMap { it.flatten() }.any { it.indexName == name }

        outcomes += ExpectationOutcome("PLAN", "uses index $name", used)
    }

    internal fun finish() {
        maxRowsRead?.let { max ->
            val actual =
                plans.sumOf { plan ->
                    plan
                        .flatten()
                        .filter { it.children.isEmpty() }
                        .sumOf { (it.actualRows ?: 0L) * (it.actualLoops ?: 1L) }
                }

            outcomes +=
                ExpectationOutcome("PLAN", "rows read <= $max", actual <= max, "actual: $actual")
        }

        maxBufferReads?.let { max ->
            val actual =
                plans.sumOf { plan ->
                    plan.flatten().sumOf {
                        (it.sharedHitBlocks ?: 0L) + (it.sharedReadBlocks ?: 0L)
                    }
                }

            outcomes +=
                ExpectationOutcome("PLAN", "buffers read <= $max", actual <= max, "actual: $actual")
        }
    }
}

class PerformanceExpectationBuilder internal constructor(
    private val plans: List<QueryPlan>,
) {

    internal val outcomes = mutableListOf<ExpectationOutcome>()

    /** Checked once the `performance { }` block finishes. CI-machine-dependent - use sparingly. */
    var maxExecutionTime: Duration? = null

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

class ExpectationBuilder internal constructor(
    private val execution: TestExecution<*>,
) {

    internal val outcomes = mutableListOf<ExpectationOutcome>()

    /** Checked once the `expect { }` block finishes. */
    var queries: Int? = null

    fun sql(block: SqlExpectationBuilder.() -> Unit) {
        val builder = SqlExpectationBuilder(execution.dataQueries.map { it.execution.query.sql })
        builder.block()
        outcomes += builder.outcomes
    }

    fun plan(block: PlanExpectationBuilder.() -> Unit) {
        val builder = PlanExpectationBuilder(requirePlans(execution.dataQueries))
        builder.block()
        builder.finish()
        outcomes += builder.outcomes
    }

    fun performance(block: PerformanceExpectationBuilder.() -> Unit) {
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
                    "$expected quer${if (expected == 1) "y" else "ies"} executed",
                    actual == expected,
                    "actual: $actual",
                )
        }
    }
}

/**
 * `execute`'s own block result is never consumed by a scenario report (unlike plain
 * [PersistenceTestHarness.execute]) - a scenario is entirely about the queries it caused, not the
 * value it returned - so this stays non-generic rather than forcing every caller to spell out a
 * type argument Kotlin can't infer from usage alone.
 */
class ScenarioBuilder internal constructor(
    private val harness: PersistenceTestHarness,
) {

    private var execution: TestExecution<*>? = null
    private val outcomes = mutableListOf<ExpectationOutcome>()

    suspend fun execute(block: suspend () -> Any?) {
        execution = harness.execute(block)
    }

    fun expect(block: ExpectationBuilder.() -> Unit) {
        val currentExecution =
            checkNotNull(execution) { "call execute { } before expect { } in a scenario" }

        val builder = ExpectationBuilder(currentExecution)
        builder.block()
        builder.finish()
        outcomes += builder.outcomes
    }

    internal fun buildReport(name: String) = ScenarioReport(name, outcomes)
}

/**
 * The consumer-facing entry point the doc's own examples build toward: `harness.scenario("search
 * playsets") { execute { ... }; expect { queries = 1; sql { usesTable("playset") }; plan {
 * noSequentialScans() } } }`.
 *
 * Throws an [AssertionError] with the full rendered [ScenarioReport] (every expectation, passed and
 * failed alike) if anything failed - a scenario is meant to be read as one report, not stopped at
 * the first broken check.
 */
suspend fun PersistenceTestHarness.scenario(
    name: String,
    block: suspend ScenarioBuilder.() -> Unit,
): ScenarioReport {
    val builder = ScenarioBuilder(this)
    builder.block()
    val report = builder.buildReport(name)
    if (!report.passed) {
        throw AssertionError(report.render())
    }

    return report
}
