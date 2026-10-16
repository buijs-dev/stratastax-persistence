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
import dev.buijs.stratastax.persistence.reactive.api.PostgresDialect
import dev.buijs.stratastax.persistence.reactive.api.VertxQueryExecutor
import dev.buijs.stratastax.persistence.reactive.api.VertxTransactionalExecutor
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [PersistenceTestHarness] end to end, against a real Postgres through a real
 * [VertxQueryExecutor]: what an execution captures, and the count and N+1 assertions on it.
 */
internal class PersistenceTestHarnessIntegrationTest {

    companion object {

        @BeforeAll
        @JvmStatic
        fun start() = TestDatabase.start()
    }

    private val dsl = PostgresDialect.dsl()
    private val recorder = QueryExecutionRecorder()
    private val executor = VertxQueryExecutor(TestDatabase.pool, listeners = listOf(recorder))
    private val transactional =
        VertxTransactionalExecutor(TestDatabase.pool, listeners = listOf(recorder))
    private val harness = PersistenceTestHarness(recorder)

    @BeforeEach
    fun seed() = TestDatabase.seed(3)

    @Test
    fun `captures the block's result and the statements it caused, and only those`() =
        runBlocking<Unit> {
            // given
            executor.executeAndGetAll(dsl.selectFrom(PLAYSET))

            // when
            val execution =
                harness.execute {
                    executor.executeAndGetFirst(dsl.selectFrom(PLAYSET).where(PLAYSET.ID.eq(1L)))
                }

            // then
            assertThat(execution.result?.string("name")).isEqualTo("playset 1")
            assertThat(execution.error).isNull()
            assertThat(execution.queries).hasSize(1)
            execution.assertSuccessful().assertQueryCount(1)
        }

    @Test
    fun `captures what the block threw instead of throwing it`() =
        runBlocking<Unit> {
            // given
            val failing = dsl.resultQuery("select * from does_not_exist")

            // when
            val execution = harness.execute { executor.executeAndGetAll(failing) }

            // then
            assertThat(execution.result).isNull()
            assertThat(execution.error).hasMessageContaining("does_not_exist")
            assertThat(
                execution.queries
                    .single()
                    .execution.failed,
            ).isTrue()
            assertThatThrownBy { execution.assertSuccessful() }
                .isInstanceOf(AssertionError::class.java)
                .hasMessageContaining("failed after 1 query")
            assertThatThrownBy { execution.assertNoFailedQueries() }.isInstanceOf(
                AssertionError::class.java,
            )
        }

    @Test
    fun `splits data statements from transaction control`() =
        runBlocking<Unit> {
            // given
            val select = dsl.selectFrom(PLAYSET)

            // when
            val execution =
                harness.execute {
                    transactional.withTransaction {
                        it.executeAndGetAll(
                            select,
                        )
                    }
                }

            // then
            assertThat(
                execution.dataQueries.map { it.execution.operation },
            ).containsExactly(QueryOperation.SELECT)
            assertThat(
                execution.transactionStatements.map { it.execution.operation },
            ).containsExactly(QueryOperation.BEGIN, QueryOperation.COMMIT)
            execution.assertQueryCount(1)
        }

    @Test
    fun `a wrong query count fails listing what ran`() =
        runBlocking<Unit> {
            // given
            val execution = harness.execute { executor.executeAndGetAll(dsl.selectFrom(PLAYSET)) }

            // when
            val thrown = catchThrowable { execution.assertQueryCount(2) }

            // then
            assertThat(thrown)
                .isInstanceOf(AssertionError::class.java)
                .hasMessageContaining("Expected 2 queries but 1 ran")
                .hasMessageContaining("SELECT: select")
        }

    @Test
    fun `the same query repeated past the threshold is flagged as N+1`() =
        runBlocking<Unit> {
            // given
            val perRow = { id: Long -> dsl.selectFrom(PLAYSET).where(PLAYSET.ID.eq(id)) }

            // when
            val execution =
                harness.execute {
                    (1L..3L).map {
                        executor.executeAndGetFirst(
                            perRow(it),
                        )
                    }
                }

            // then
            assertThatThrownBy { execution.assertNoNPlusOne(threshold = 3) }
                .isInstanceOf(AssertionError::class.java)
                .hasMessageContaining("Repeated query (3x)")
            execution.assertNoNPlusOne(threshold = 4)
        }

    @Test
    fun `an analyzer adds a plan to every successful data statement only`() =
        runBlocking<Unit> {
            // given
            val analyzing = PersistenceTestHarness(recorder, TestDatabase.postgres.planAnalyzer())

            // when
            val execution =
                analyzing.execute {
                    transactional.withTransaction { it.executeAndGetAll(dsl.selectFrom(PLAYSET)) }
                }

            // then
            assertThat(execution.dataQueries.single().plan).isNotNull
            assertThat(execution.transactionStatements.map { it.plan }).containsOnlyNulls()
        }
}
