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

import dev.buijs.stratastax.persistence.reactive.api.PostgresDialect
import dev.buijs.stratastax.persistence.reactive.api.VertxQueryExecutor
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** The [scenario] DSL: every check runs, and a failing scenario throws its full report. */
internal class ScenarioIntegrationTest {

    companion object {

        @BeforeAll
        @JvmStatic
        fun start() = TestDatabase.start()
    }

    private val dsl = PostgresDialect.dsl()
    private val recorder = QueryExecutionRecorder()
    private val executor = VertxQueryExecutor(TestDatabase.pool, listeners = listOf(recorder))
    private val harness = PersistenceTestHarness(recorder, TestDatabase.postgres.planAnalyzer())

    @BeforeEach
    fun seed() = TestDatabase.seed(20_000)

    @Test
    fun `a passing scenario returns its report`() =
        runBlocking<Unit> {
            // given
            val lookup = dsl.selectFrom(PLAYSET).where(PLAYSET.NAME.eq("playset 7"))

            // when
            val report =
                harness.scenario("find by name") {
                    execute { executor.executeAndGetAll(lookup) }

                    expect {
                        queries = 1
                        sql { usesPredicate("name") }

                        plan {
                            noSequentialScans()
                            usesIndex("playset_name_idx")
                            maxRowsRead = 1
                            maxBufferReads = 100
                        }

                        performance { maxExecutionTime = 5.seconds }
                    }
                }

            // then
            assertThat(report.passed).isTrue()
            assertThat(
                report.render(),
            ).startsWith("✓ find by name").contains("PLAN\n  ✓ no sequential scans")
        }

    @Test
    fun `a failing scenario runs every check and throws the whole report`() {
        // given
        val everything = dsl.selectFrom(PLAYSET)

        // when
        val thrown =
            catchThrowable {
                runBlocking {
                    harness.scenario("read everything") {
                        execute { executor.executeAndGetAll(everything) }

                        expect {
                            queries = 2
                            sql { usesTable("playset") }

                            plan { noSequentialScans() }
                        }
                    }
                }
            }

        // then
        assertThat(thrown)
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("✗ read everything")
            .hasMessageContaining("✗ 2 queries executed")
            .hasMessageContaining("✓ table playset")
            .hasMessageContaining("✗ no sequential scans")
    }

    @Test
    fun `expect before execute is a usage error`() {
        // given
        val scenario: suspend ScenarioBuilder.() -> Unit = { expect { queries = 0 } }

        // when
        val thrown = catchThrowable { runBlocking { harness.scenario("no execute", scenario) } }

        // then
        assertThat(thrown)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("call execute { } before expect { }")
    }
}
