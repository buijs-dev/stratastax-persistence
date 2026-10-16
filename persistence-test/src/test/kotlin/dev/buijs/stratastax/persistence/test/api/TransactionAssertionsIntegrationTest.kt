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

import dev.buijs.stratastax.persistence.core.api.QueryOperation
import dev.buijs.stratastax.persistence.reactive.api.QueryExecutor
import dev.buijs.stratastax.persistence.reactive.api.VertxTransactionalExecutor
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

// Named like the jOOQ-generated table it stands in for.
@Suppress("ktlint:standard:class-naming")
private object TX_GAME : org.jooq.impl.TableImpl<org.jooq.Record>(DSL.name("tx_game")) {

    val NAME: org.jooq.TableField<org.jooq.Record, String> =
        createField(DSL.name("name"), org.jooq.impl.SQLDataType.VARCHAR)
}

/**
 * The transaction side of [PersistenceTestHarness]: a real [VertxTransactionalExecutor] reports its
 * `BEGIN`/`SAVEPOINT`/`COMMIT`/... statements to the recorder, the data assertions ignore them and
 * the transaction assertions read them. Requires Docker, skipped without it.
 */
internal class TransactionAssertionsIntegrationTest {

    companion object {

        private val database = PostgresTestDatabase()
        private lateinit var pool: io.vertx.mutiny.sqlclient.Pool
        private val dsl = DSL.using(SQLDialect.POSTGRES)

        @BeforeAll
        @JvmStatic
        fun setUp() {
            database.start()
            database.enableStatementLogging()
            database.executeDdl("CREATE TABLE tx_game (name VARCHAR(255) NOT NULL)")

            val container = database.container
            pool =
                io.vertx.mutiny.pgclient.PgBuilder
                    .pool()
                    .connectingTo(
                        io.vertx.pgclient
                            .PgConnectOptions()
                            .setHost(container.host)
                            .setPort(container.firstMappedPort)
                            .setDatabase(container.databaseName)
                            .setUser(container.username)
                            .setPassword(container.password),
                    ).with(io.vertx.sqlclient.PoolOptions())
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
    private val transactional = VertxTransactionalExecutor(pool, listeners = listOf(recorder))
    private val executor = transactional.ambientQueryExecutor()
    private val container = database.container
    private val harness =
        PersistenceTestHarness(
            recorder,
            PostgresQueryPlanAnalyzer(container.jdbcUrl, container.username, container.password),
        )

    private suspend fun QueryExecutor.insert(name: String?) {
        executeUpdate(dsl.insertInto(TX_GAME).set(TX_GAME.NAME, name))
    }

    @Test
    fun `a committed unit of work passes the transaction assertions and keeps the data query count`() {
        runBlocking {
            val execution =
                harness.execute {
                    transactional.transaction {
                        executor.insert("Catan")
                        executor.insert("Azul")
                    }
                }

            execution
                .assertSuccessful()
                .assertQueryCount(2)
                .assertSingleTransaction()
                .assertCommitted()
                .assertNoFailedQueries()
            assertThat(execution.queries.map { it.execution.operation })
                .containsExactly(
                    QueryOperation.BEGIN,
                    QueryOperation.INSERT,
                    QueryOperation.INSERT,
                    QueryOperation.COMMIT,
                )
            assertThat(execution.transactionStatements.map { it.plan }).containsOnlyNulls()
            assertThat(execution.dataQueries.map { it.plan }).doesNotContainNull()
        }
    }

    @Test
    fun `a rolled-back unit of work passes assertRolledBack and fails assertCommitted with the full flow`() {
        runBlocking {
            val execution =
                harness.execute {
                    transactional.transaction {
                        executor.insert("discarded")
                        error("force a rollback")
                    }
                }

            execution.assertRolledBack()
            assertThatThrownBy { execution.assertCommitted() }
                .isInstanceOf(AssertionError::class.java)
                .hasMessageContaining("they ended with ROLLBACK")
                .hasMessageContaining("BEGIN: BEGIN")
                .hasMessageContaining("INSERT: insert into \"tx_game\"")
        }
    }

    @Test
    fun `the database can be queried directly and logs every statement server-side`() {
        runBlocking {
            database.executeDdl("TRUNCATE tx_game")
            transactional.transaction { executor.insert("Wingspan") }

            assertThat(database.queryForLong("select count(*) from tx_game")).isEqualTo(1)
            assertThat(database.query("select name from tx_game"))
                .containsExactly(mapOf("name" to "Wingspan"))
            // The server-side log shows BEGIN, the INSERT and COMMIT on one backend process.
            val logged =
                database.logs().lines().filter {
                    it.contains("statement:") || it.contains("execute")
                }

            val pidOf = { marker: String ->
                logged.last { it.contains(marker) }.substringAfter("pid=").substringBefore(' ')
            }

            assertThat(pidOf("BEGIN")).isEqualTo(pidOf("insert into")).isEqualTo(pidOf("COMMIT"))
        }
    }

    @Test
    fun `writes in autocommit mode fail assertSingleTransaction`() {
        runBlocking {
            val execution =
                harness.execute {
                    executor.insert("first")
                    executor.insert("second")
                }

            assertThatThrownBy { execution.assertSingleTransaction() }
                .isInstanceOf(AssertionError::class.java)
                .hasMessageContaining("[tx -]")
        }
    }

    @Test
    fun `a failed statement is recorded with its error and has no plan`() {
        runBlocking {
            val execution =
                harness.execute {
                    transactional.transaction {
                        runCatching { transactional.withTransaction { tx -> tx.insert(null) } }
                    }
                }

            val failed = execution.queries.single { it.execution.failed }

            assertThat(failed.execution.operation).isEqualTo(QueryOperation.INSERT)
            assertThat(failed.plan).isNull()
            assertThat(execution.queries.map { it.execution.operation })
                .containsExactly(
                    QueryOperation.BEGIN,
                    QueryOperation.SAVEPOINT,
                    QueryOperation.INSERT,
                    QueryOperation.ROLLBACK_TO_SAVEPOINT,
                    QueryOperation.COMMIT,
                )
            assertThatThrownBy { execution.assertNoFailedQueries() }
                .isInstanceOf(AssertionError::class.java)
                .hasMessageContaining("✗")
        }
    }
}
