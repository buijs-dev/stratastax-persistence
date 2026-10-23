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
package dev.buijs.stratastax.persistence.postgres.test.api

import dev.buijs.stratastax.persistence.core.spi.RenderedQuery
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [PostgresQueryPlanAnalyzer] against a real Postgres: plan parsing, bind values of every kind,
 * and that analyzing a write leaves no trace.
 */
internal class PostgresQueryPlanAnalyzerIntegrationTest {

    companion object {

        @BeforeAll
        @JvmStatic
        fun start() = TestDatabase.start()
    }

    private val analyzer = TestDatabase.postgres.planAnalyzer()

    @BeforeEach
    fun seed() = TestDatabase.seed(10)

    private fun analyze(
        sql: String,
        params: List<Any?> = emptyList(),
    ) = runBlocking { analyzer.analyze(RenderedQuery(sql, params)) }

    @Test
    fun `parses the plan tree of a query`() {
        // given
        val query = "SELECT id, name, payload FROM playset WHERE id = \$1::bigint"

        // when
        val plan = analyze(query, listOf(1L))

        // then
        assertThat(plan.root.relationName).isEqualTo("playset")
        assertThat(plan.root.actualRows).isEqualTo(1L)
        assertThat(plan.root.renderTree()).contains("relation=playset")
        assertThat(plan.planningTime.isNegative()).isFalse()
    }

    @Test
    fun `analyzing an insert, update or delete leaves the data untouched`() {
        // given
        val writes =
            listOf(
                "INSERT INTO playset (name) VALUES ('analyzed')",
                "UPDATE playset SET name = 'analyzed'",
                "DELETE FROM playset",
            )

        // when
        writes.forEach { analyze(it) }

        // then
        assertThat(TestDatabase.postgres.queryForLong("SELECT count(*) FROM playset")).isEqualTo(10)
        assertThat(
            TestDatabase.postgres.queryForLong(
                "SELECT count(*) FROM playset WHERE name = 'analyzed'",
            ),
        ).isZero()
    }

    @Test
    fun `binds JSON and array parameters`() {
        // given
        val json = "SELECT id FROM playset WHERE payload = \$1::jsonb"
        val array = "SELECT id FROM playset WHERE id = ANY(\$1::bigint[])"

        // when
        val plans =
            listOf(
                analyze(json, listOf(mapOf("a" to 1))),
                analyze(array, listOf(arrayOf(1L, 2L, 3L))),
            )

        // then
        assertThat(plans.map { it.root.relationName }).containsOnly("playset")
    }
}
