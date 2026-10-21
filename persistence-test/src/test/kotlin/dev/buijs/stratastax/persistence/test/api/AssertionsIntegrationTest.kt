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

import dev.buijs.stratastax.persistence.postgres.api.PostgresDialect
import dev.buijs.stratastax.persistence.reactive.api.VertxQueryExecutor
import dev.buijs.stratastax.persistence.reactive.api.VertxTransactionalExecutor
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The plan, SQL and transaction assertions on a [TestExecution], against a real Postgres planner
 * on a table large enough for an index to be worth using.
 */
internal class AssertionsIntegrationTest {

    companion object {

        @BeforeAll
        @JvmStatic
        fun start() {
            TestDatabase.start()
        }
    }

    private val recorder = QueryExecutionRecorder()
    private val executor =
        VertxQueryExecutor(TestDatabase.pool, PostgresDialect, listeners = listOf(recorder))
    private val transactional =
        VertxTransactionalExecutor(TestDatabase.pool, PostgresDialect, listeners = listOf(recorder))
    private val harness = PersistenceTestHarness(recorder, TestDatabase.postgres.planAnalyzer())

    @BeforeEach
    fun seed() = TestDatabase.seed(20_000)

    private fun byName() =
        runBlocking {
            harness.execute {
                executor.query(
                    "SELECT id, name, payload FROM playset WHERE name = \$1::varchar",
                    listOf("playset 42"),
                )
            }
        }

    private fun everything() =
        runBlocking { harness.execute { executor.query("SELECT id, name, payload FROM playset") } }

    @Test
    fun `an indexed lookup passes the plan assertions`() {
        // given
        val execution = byName()

        // when
        execution
            .assertNoSequentialScans()
            .assertUsesIndex("playset_name_idx")
            .assertMaxRowsRead(1)
            .assertMaxBufferReads(100)
            .assertExecutionTimeBelow(5.seconds)

        // then
    }

    @Test
    fun `a full table read fails the plan assertions`() {
        // given
        val execution = everything()

        // when
        val thrown1 =
            catchThrowable {
                execution.assertNoSequentialScans()
            }

        val thrown2 =
            catchThrowable {
                execution.assertUsesIndex(
                    "playset_name_idx",
                )
            }

        val thrown3 =
            catchThrowable {
                execution.assertMaxRowsRead(
                    100,
                )
            }

        val thrown4 =
            catchThrowable {
                execution.assertMaxBufferReads(1)
            }

        val thrown5 =
            catchThrowable {
                execution.assertExecutionTimeBelow(0.milliseconds)
            }

        // then
        assertThat(thrown1).hasMessageContaining("Sequential scan on: playset")
        assertThat(thrown2).hasMessageContaining("wasn't")
        assertThat(thrown3).hasMessageContaining("rows read, but 20000")
        assertThat(thrown4).hasMessageContaining("shared buffer blocks")
        assertThat(thrown5).hasMessageContaining("Expected execution time below")
    }

    @Test
    fun `plan assertions need an analyzer`() =
        runBlocking<Unit> {
            // given
            val execution =
                PersistenceTestHarness(recorder).execute {
                    executor.query("SELECT id, name, payload FROM playset")
                }

            // when
            val thrown =
                catchThrowable {
                    execution.assertNoSequentialScans()
                }

            // then
            assertThat(
                thrown,
            ).hasMessageContaining("build the PersistenceTestHarness with a QueryPlanAnalyzer")
        }

    @Test
    fun `SQL assertions check what the statements reference`() {
        // given
        val execution = byName()

        // when
        val thrown = catchThrowable { execution.assertQuery { usesTable("game") } }

        // then
        execution.assertQuery {
            usesTable("playset")
            usesColumn("name")
            usesPredicate("name")
            doesNotContain("order by")
        }

        assertThat(thrown)
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("✗ table game")
    }

    @Test
    fun `transaction assertions tell committed, rolled back and single-transaction work apart`() =
        runBlocking<Unit> {
            // given
            val insert = "INSERT INTO playset (name) VALUES ('new')"

            // when
            val committed =
                harness.execute {
                    transactional.withTransaction {
                        it.execute(insert)
                        it.execute(insert)
                    }
                }

            val rolledBack =
                harness.execute {
                    transactional.withTransaction {
                        it.execute(insert)
                        error("undo")
                    }
                }

            val autocommit =
                harness.execute {
                    executor.execute(insert)
                    executor.execute(insert)
                }

            // then
            committed.assertCommitted().assertSingleTransaction()
            rolledBack.assertRolledBack()
            assertThatThrownBy { rolledBack.assertCommitted() }.hasMessageContaining(
                "ended with ROLLBACK",
            )
            assertThatThrownBy {
                autocommit.assertSingleTransaction()
            }.hasMessageContaining("autocommit shown as '-'")
            assertThatThrownBy { autocommit.assertCommitted() }.hasMessageContaining(
                "no transaction ran",
            )
        }
}
