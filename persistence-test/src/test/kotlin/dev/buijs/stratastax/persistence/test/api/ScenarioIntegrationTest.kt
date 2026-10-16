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

import dev.buijs.stratastax.persistence.reactive.api.VertxQueryExecutor
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

// Named like the jOOQ-generated table it stands in for.
@Suppress("ktlint:standard:class-naming")
private object SCENARIO_PLAYSET : org.jooq.impl.TableImpl<org.jooq.Record>(DSL.name("playset")) {

    val ID: org.jooq.TableField<org.jooq.Record, Long> =
        createField(DSL.name("id"), org.jooq.impl.SQLDataType.BIGINT)
    val NAME: org.jooq.TableField<org.jooq.Record, String> =
        createField(DSL.name("name"), org.jooq.impl.SQLDataType.VARCHAR)
}

/**
 * Exercises [PersistenceTestHarness.scenario] - the consumer-facing DSL combining query-count,
 * SQL-semantic, and plan-based expectations into one report - against a real Postgres.
 *
 * **Requires Docker.**
 */
internal class ScenarioIntegrationTest {

    companion object {

        private val database = PostgresTestDatabase()
        private lateinit var pool: io.vertx.mutiny.sqlclient.Pool
        private val dsl = DSL.using(SQLDialect.POSTGRES)

        @BeforeAll
        @JvmStatic
        fun setUp() {
            database.start()
            database.executeDdl(
                """
                CREATE TABLE playset (
                    id BIGSERIAL PRIMARY KEY,
                    name VARCHAR(255) NOT NULL
                )
                """.trimIndent(),
            )

            val container = database.container
            val connectOptions =
                io.vertx.pgclient
                    .PgConnectOptions()
                    .setHost(container.host)
                    .setPort(container.firstMappedPort)
                    .setDatabase(container.databaseName)
                    .setUser(container.username)
                    .setPassword(container.password)

            pool =
                io.vertx.mutiny.pgclient.PgBuilder
                    .pool()
                    .connectingTo(connectOptions)
                    .with(io.vertx.sqlclient.PoolOptions())
                    .build()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            if (::pool.isInitialized) pool.close().await().indefinitely()
            database.stop()
        }
    }

    private val recorder = QueryExecutionRecorder()
    private val executor = VertxQueryExecutor(pool, listeners = listOf(recorder))
    private val analyzer =
        PostgresQueryPlanAnalyzer(
            jdbcUrl = database.container.jdbcUrl,
            username = database.container.username,
            password = database.container.password,
        )
    private val harness = PersistenceTestHarness(recorder, analyzer)

    @Test
    fun `a passing scenario reports every expectation as satisfied`() {
        runBlocking {
            val id =
                executor
                    .executeReturning(
                        dsl
                            .insertInto(SCENARIO_PLAYSET)
                            .set(mapOf(SCENARIO_PLAYSET.NAME to "Catan"))
                            .returning(),
                    )!!
                    .long("id")

            val report =
                harness.scenario("find playset by id") {
                    execute {
                        executor.executeAndGetFirst(
                            dsl
                                .select(SCENARIO_PLAYSET.ID, SCENARIO_PLAYSET.NAME)
                                .from(SCENARIO_PLAYSET)
                                .where(SCENARIO_PLAYSET.ID.eq(id)),
                        )
                    }

                    expect {
                        queries = 1
                        sql {
                            usesTable("playset")
                            usesPredicate("id")
                        }

                        plan {
                            noSequentialScans()
                            usesIndex("playset_pkey")
                            maxRowsRead = 1
                        }

                        performance { maxExecutionTime = 5.seconds }
                    }
                }

            assertThat(report.passed).isTrue()
            assertThat(report.outcomes.isNotEmpty()).isTrue()
            assertThat(report.render().contains("✓ find playset by id")).isTrue()
        }
    }

    @Test
    fun `a failing scenario reports every broken expectation, not just the first`() {
        runBlocking {
            val error =
                catchThrowableOfType(AssertionError::class.java) {
                    runBlocking {
                        harness.scenario("broken expectations") {
                            execute {
                                executor.executeAndGetAll(
                                    dsl.select(SCENARIO_PLAYSET.ID).from(SCENARIO_PLAYSET),
                                )
                            }

                            expect {
                                queries = 99
                                sql { usesTable("does_not_exist") }
                            }
                        }
                    }
                }

            assertThat(error.message!!.contains("99 queries executed")).isTrue()
            assertThat(error.message!!.contains("table does_not_exist")).isTrue()
        }
    }
}
