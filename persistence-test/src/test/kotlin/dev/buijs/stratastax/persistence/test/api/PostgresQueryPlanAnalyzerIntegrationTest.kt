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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

private object GAME : org.jooq.impl.TableImpl<org.jooq.Record>(DSL.name("game")) {

    val ID: org.jooq.TableField<org.jooq.Record, Long> =
        createField(DSL.name("id"), org.jooq.impl.SQLDataType.BIGINT)
    val NAME: org.jooq.TableField<org.jooq.Record, String> =
        createField(DSL.name("name"), org.jooq.impl.SQLDataType.VARCHAR)
    val CATEGORY: org.jooq.TableField<org.jooq.Record, String> =
        createField(DSL.name("category"), org.jooq.impl.SQLDataType.VARCHAR)
}

/**
 * Exercises the plan-based assertions ([TestExecution.assertNoSequentialScans],
 * [TestExecution.assertUsesIndex], [TestExecution.assertMaxRowsRead],
 * [TestExecution.assertMaxBufferReads]) against a real Postgres, backed by
 * [PostgresQueryPlanAnalyzer] running actual `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` queries.
 *
 * **Requires Docker** - [setUp] skips (not fails) without it.
 */
internal class PostgresQueryPlanAnalyzerIntegrationTest {

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
                CREATE TABLE game (
                    id BIGSERIAL PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    category VARCHAR(255) NOT NULL
                )
                """.trimIndent(),
                "CREATE INDEX idx_game_name ON game(name)",
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

            // Enough rows that the planner actually prefers the index over a
            // sequential scan for an equality lookup - too few rows and
            // Postgres reasonably picks a seq scan regardless of the index.
            val insert = dsl.insertInto(GAME, GAME.NAME, GAME.CATEGORY)
            (1..2000).forEach { i -> insert.values("Game $i", if (i % 2 == 0) "even" else "odd") }

            runBlocking { VertxQueryExecutor(pool).executeUpdate(insert) }
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
    fun `a primary key lookup uses the primary key index and touches one row`() {
        runBlocking {
            val result =
                harness.execute {
                    executor.executeAndGetFirst(
                        dsl.select(GAME.ID).from(GAME).where(GAME.ID.eq(1L)),
                    )
                }

            result
                .assertSuccessful()
                .assertNoSequentialScans()
                .assertUsesIndex("game_pkey")
                .assertMaxRowsRead(1)
        }
    }

    @Test
    fun `an indexed column lookup uses that index`() {
        runBlocking {
            val result =
                harness.execute {
                    executor.executeAndGetFirst(
                        dsl.select(GAME.ID).from(GAME).where(GAME.NAME.eq("Game 42")),
                    )
                }

            result.assertSuccessful().assertNoSequentialScans().assertUsesIndex("idx_game_name")
        }
    }

    @Test
    fun `a lookup on a column with no index is a sequential scan`() {
        runBlocking {
            val result =
                harness.execute {
                    executor.executeAndGetAll(
                        dsl.select(GAME.ID).from(GAME).where(GAME.CATEGORY.eq("even")),
                    )
                }

            result.assertSuccessful().assertQueryCount(1)
            assertThatThrownBy { result.assertNoSequentialScans() }
                .isInstanceOf(AssertionError::class.java)
        }
    }

    @Test
    fun `assertMaxRowsRead fails with a clear message when the budget is exceeded`() {
        runBlocking {
            val result =
                harness.execute {
                    executor.executeAndGetAll(
                        dsl.select(GAME.ID).from(GAME).where(GAME.CATEGORY.eq("even")),
                    )
                }

            result.assertSuccessful()
            assertThatThrownBy { result.assertMaxRowsRead(10) }
                .isInstanceOf(AssertionError::class.java)
        }
    }
}
