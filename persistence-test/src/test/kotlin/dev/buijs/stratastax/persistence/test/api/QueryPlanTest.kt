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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/** The metrics and the rendering of a [QueryPlan]. */
class QueryPlanTest {

    private fun node(
        type: String,
        relation: String? = null,
        index: String? = null,
        rows: Long? = null,
        loops: Long? = null,
        hits: Long? = null,
        reads: Long? = null,
        children: List<PlanNode> = emptyList(),
    ) = PlanNode(type, relation, index, rows, null, loops, hits, reads, null, children)

    private val plan =
        QueryPlan(
            planningTime = 1.milliseconds,
            executionTime = 2.milliseconds,
            root =
                node(
                    "Nested Loop",
                    hits = 1,
                    children =
                        listOf(
                            node(
                                "Seq Scan",
                                relation = "playset",
                                rows = 10,
                                loops = 1,
                                hits = 2,
                                reads = 3,
                            ),
                            node(
                                "Index Scan",
                                relation = "game",
                                index = "game_pkey",
                                rows = 1,
                                loops = 10,
                                hits = 4,
                            ),
                        ),
                ),
        )

    @Test
    fun `computes rows read from the leaves times their loops`() {
        // given
        val subject = plan

        // when
        val rowsRead = subject.rowsRead()

        // then
        assertThat(rowsRead).isEqualTo(20)
    }

    @Test
    fun `sums the buffer reads of every node`() {
        // given
        val subject = plan

        // when
        val buffers = subject.bufferReads()

        // then
        assertThat(buffers).isEqualTo(10)
    }

    @Test
    fun `finds sequential scans and used indexes`() {
        // given
        val subject = plan

        // when
        val scans = subject.sequentialScans().map { it.relationName }

        val indexes = subject.indexesUsed()

        // then
        assertThat(scans).containsExactly("playset")
        assertThat(indexes).containsExactly("game_pkey")
    }

    @Test
    fun `renders the plan shape without volatile numbers`() {
        // given
        val subject = plan.root

        // when
        val tree = subject.renderTree()

        // then
        assertThat(
            tree,
        ).isEqualTo(
            "Nested Loop\n  Seq Scan (relation=playset)\n  Index Scan (relation=game, index=game_pkey)",
        )
    }

    @Test
    fun `converts native placeholders to JDBC ones outside literals`() {
        // given
        val sql = "select '\$1', \"\$2\" from t where a = \$1 and b in (\$2, \$10)"

        // when
        val converted = toJdbcPlaceholders(sql)

        // then
        assertThat(converted).isEqualTo("select '\$1', \"\$2\" from t where a = ? and b in (?, ?)")
    }
}
