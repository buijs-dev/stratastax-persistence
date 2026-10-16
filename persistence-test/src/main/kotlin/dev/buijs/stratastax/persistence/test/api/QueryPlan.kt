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

import dev.buijs.stratastax.persistence.core.spi.RenderedQuery
import kotlin.time.Duration

/** Runs a statement through the database's own planner to find out what it actually did. */
public interface QueryPlanAnalyzer {

    public suspend fun analyze(query: RenderedQuery): QueryPlan
}

/**
 * One node of an `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` plan tree: the fields the assertions
 * use, not every field Postgres reports.
 */
public data class PlanNode(
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
    public fun flatten(): List<PlanNode> = listOf(this) + children.flatMap { it.flatten() }

    /** Whether this node scans a whole table. */
    public val isSequentialScan: Boolean
        get() = nodeType == "Seq Scan"

    /**
     * The plan's shape - node types, relations and indexes, without row counts, buffers or timing,
     * which vary from run to run - one node per line, children indented.
     */
    public fun renderTree(indent: String = ""): String =
        buildString {
            append(indent).append(nodeType)
            val details =
                listOfNotNull(relationName?.let { "relation=$it" }, indexName?.let { "index=$it" })
            if (details.isNotEmpty()) {
                append(
                    details.joinToString(", ", prefix = " (", postfix = ")"),
                )
            }

            children.forEach { child -> append("\n").append(child.renderTree("$indent  ")) }
        }
}

/** The plan of one statement, see [QueryPlanAnalyzer]. */
public data class QueryPlan(
    val planningTime: Duration,
    val executionTime: Duration,
    val root: PlanNode,
) {

    /** Every node of the plan, depth-first. */
    public fun flatten(): List<PlanNode> = root.flatten()

    /** The nodes that scan a whole table. */
    public fun sequentialScans(): List<PlanNode> = flatten().filter { it.isSequentialScan }

    /** The names of the indexes the plan used. */
    public fun indexesUsed(): Set<String> = flatten().mapNotNull { it.indexName }.toSet()

    /** The rows the leaf nodes read: their actual rows times their loops. */
    public fun rowsRead(): Long =
        flatten().filter { it.children.isEmpty() }.sumOf {
            (it.actualRows ?: 0L) *
                (it.actualLoops ?: 1L)
        }

    /** The shared buffer blocks read, from cache and from disk. */
    public fun bufferReads(): Long =
        flatten().sumOf {
            (it.sharedHitBlocks ?: 0L) +
                (it.sharedReadBlocks ?: 0L)
        }
}
