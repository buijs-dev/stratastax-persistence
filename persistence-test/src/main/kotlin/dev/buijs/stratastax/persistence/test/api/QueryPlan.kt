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

/**
 * One node of a Postgres `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` plan tree - a direct model of
 * the fields this testkit's assertions actually consult, not a full mirror of every field Postgres
 * can emit.
 */
data class PlanNode(
    val nodeType: String,
    val relationName: String?,
    val indexName: String?,
    val actualRows: Long?,
    val planRows: Long?,
    val actualLoops: Long?,
    val sharedHitBlocks: Long?,
    val sharedReadBlocks: Long?,
    val rowsRemovedByFilter: Long?,
    val children: List<PlanNode>,
) {

    /** This node and every descendant, depth-first. */
    fun flatten(): List<PlanNode> = listOf(this) + children.flatMap { it.flatten() }
}

/**
 * A `Node Type` is a sequential scan if it's exactly `"Seq Scan"` (Postgres never varies this
 * string).
 */
fun PlanNode.isSequentialScan(): Boolean = nodeType == "Seq Scan"

/**
 * A stable, human-readable rendering of the plan's *shape* - node types, relations, indexes -
 * deliberately omitting row counts/buffer stats/timing, which vary run to run even for an identical
 * query against unchanged data (autovacuum, cache warmth, ...) and would make a snapshot comparison
 * flaky-by-design rather than catching a real structural change (e.g. an `Index Scan` silently
 * turning into a `Seq Scan`/`Hash Join`).
 */
fun PlanNode.renderTree(indent: String = ""): String =
    buildString {
        append(indent)
        append(nodeType)
        val details =
            listOfNotNull(relationName?.let { "relation=$it" }, indexName?.let { "index=$it" })
        if (details.isNotEmpty()) {
            append(" (")
            append(details.joinToString(", "))
            append(")")
        }

        children.forEach { child ->
            append("\n")
            append(child.renderTree("$indent  "))
        }
    }

/** The parsed result of running `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` for one query. */
data class QueryPlan(
    val planningTime: Duration,
    val executionTime: Duration,
    val root: PlanNode,
) {

    fun flatten(): List<PlanNode> = root.flatten()
}
