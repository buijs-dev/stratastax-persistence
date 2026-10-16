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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

private object PLAYSET : org.jooq.impl.TableImpl<org.jooq.Record>(DSL.name("playset")) {

    val ID: org.jooq.TableField<org.jooq.Record, Long> =
        createField(DSL.name("id"), org.jooq.impl.SQLDataType.BIGINT)
    val NAME: org.jooq.TableField<org.jooq.Record, String> =
        createField(DSL.name("name"), org.jooq.impl.SQLDataType.VARCHAR)
}

/**
 * Exercises [PersistenceTestHarness] end-to-end against a real Postgres (via
 * [PostgresTestDatabase]) and a real [VertxQueryExecutor] - the same combination a consumer
 * project's own persistence tests would wire up.
 *
 * **Requires Docker** - [setUp] skips (not fails) without it, same as `persistence-vertx`'s own
 * `VertxQueryExecutorIntegrationTest`.
 */
internal class PersistenceTestHarnessIntegrationTest {

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
    private val harness = PersistenceTestHarness(recorder)

    @Test
    fun `a single select is reported as one successful query`() =
        runBlocking {
            val inserted =
                executor.executeReturning(
                    dsl.insertInto(PLAYSET).set(mapOf(PLAYSET.NAME to "Catan")).returning(),
                )
            val id = inserted!!.long("id")

            val result =
                harness.execute {
                    executor.executeAndGetFirst(
                        dsl.select(PLAYSET.ID, PLAYSET.NAME).from(PLAYSET).where(PLAYSET.ID.eq(id)),
                    )
                }

            result.assertSuccessful().assertQueryCount(1)
            assertThat(result.result?.string("name")).isEqualTo("Catan")
        }

    @Test
    fun `the same query repeated past the threshold is flagged as N+1`() {
        runBlocking {
            val ids =
                (1..3).map { i ->
                    executor
                        .executeReturning(
                            dsl
                                .insertInto(PLAYSET)
                                .set(mapOf(PLAYSET.NAME to "Expansion $i"))
                                .returning(),
                        )!!
                        .long("id")
                }

            val result =
                harness.execute {
                    ids.map { id ->
                        executor.executeAndGetFirst(
                            dsl.select(PLAYSET.ID).from(PLAYSET).where(PLAYSET.ID.eq(id)),
                        )
                    }
                }

            result.assertSuccessful().assertQueryCount(3)
            assertThatThrownBy { result.assertNoNPlusOne(threshold = 3) }
                .isInstanceOf(AssertionError::class.java)
        }
    }

    @Test
    fun `a single batched query does not trip the N+1 assertion`() {
        runBlocking {
            val result =
                harness.execute { executor.executeAndGetAll(dsl.select(PLAYSET.ID).from(PLAYSET)) }

            result.assertSuccessful().assertQueryCount(1).assertNoNPlusOne()
        }
    }
}
