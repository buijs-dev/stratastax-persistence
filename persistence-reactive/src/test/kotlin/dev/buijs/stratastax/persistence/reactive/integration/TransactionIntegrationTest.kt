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
package dev.buijs.stratastax.persistence.reactive.integration

import dev.buijs.stratastax.persistence.core.api.BatchFailedException
import dev.buijs.stratastax.persistence.core.api.BatchItemResult
import dev.buijs.stratastax.persistence.core.api.TransactionIsolation
import dev.buijs.stratastax.persistence.core.api.TransactionOptions
import dev.buijs.stratastax.persistence.core.api.TransactionPropagation
import dev.buijs.stratastax.persistence.core.api.orThrow
import dev.buijs.stratastax.persistence.core.spi.QueryExecution
import dev.buijs.stratastax.persistence.core.spi.QueryExecutionListener
import dev.buijs.stratastax.persistence.core.spi.QueryOperation
import dev.buijs.stratastax.persistence.reactive.api.VertxQueryExecutor
import dev.buijs.stratastax.persistence.reactive.api.VertxTransactionalExecutor
import dev.buijs.stratastax.persistence.reactive.spi.QueryExecutor
import dev.buijs.stratastax.persistence.reactive.sqlfirst.PlaysetSqlStore
import dev.buijs.stratastax.persistence.reactive.testfixtures.CreatePlayset
import dev.buijs.stratastax.persistence.reactive.testfixtures.Database
import dev.buijs.stratastax.persistence.reactive.testfixtures.Seed
import dev.buijs.stratastax.persistence.test.api.QueryExecutionRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * [VertxTransactionalExecutor] against a real Postgres: what commits, what rolls back, how calls
 * join a running transaction through savepoints, and the guards around misuse (cancellation, a
 * failing listener, concurrent use, a leaked coroutine).
 */
internal class TransactionIntegrationTest {

    companion object {

        @BeforeAll
        @JvmStatic
        fun start() = Database.start()
    }

    private val recorder = QueryExecutionRecorder()
    private val transactional =
        VertxTransactionalExecutor(Database.pool, listeners = listOf(recorder))
    private val ambient = transactional.ambientQueryExecutor()

    @BeforeEach
    fun clean() {
        Database.truncate()
    }

    private suspend fun QueryExecutor.insertGame(name: String?) {
        execute(
            "INSERT INTO game (public_id, name) VALUES (\$1::uuid, \$2::varchar)",
            listOf(UUID.randomUUID(), name),
        )
    }

    private suspend fun QueryExecutor.transactionId(): Long =
        checkNotNull(queryFirst("SELECT txid_current() AS id")).long("id")

    private fun committedGames(): List<Any?> =
        Database.query("SELECT name FROM game ORDER BY id").map { it["name"] }

    private fun operations(): List<QueryOperation> = recorder.executions().map { it.operation }

    @Test
    fun `a listener sees a unit of work's statements in order, under one transaction id`() =
        runBlocking<Unit> {
            // given
            val block: suspend () -> Unit = {
                ambient.insertGame("outer")
                transactional.withTransaction { it.insertGame("nested") }
            }

            // when
            transactional.transaction(block = block)

            // then
            assertThat(operations())
                .containsExactly(
                    QueryOperation.BEGIN,
                    QueryOperation.INSERT,
                    QueryOperation.SAVEPOINT,
                    QueryOperation.INSERT,
                    QueryOperation.RELEASE_SAVEPOINT,
                    QueryOperation.COMMIT,
                )
            assertThat(
                recorder
                    .executions()
                    .map { it.transactionId }
                    .distinct(),
            ).hasSize(1).doesNotContainNull()
        }

    @Test
    fun `a statement outside any transaction is reported without a transaction id`() =
        runBlocking<Unit> {
            // given
            val outside = VertxQueryExecutor(Database.pool, listeners = listOf(recorder))

            // when
            outside.insertGame("autocommit")

            // then
            assertThat(recorder.executions().single().transactionId).isNull()
        }

    @Test
    fun `a nested call joins the running transaction`() =
        runBlocking<Unit> {
            // given
            val ids = mutableListOf<Long>()

            // when
            transactional.withTransaction { outer ->
                ids += outer.transactionId()
                transactional.withTransaction { nested -> ids += nested.transactionId() }
            }

            // then
            assertThat(ids.distinct()).hasSize(1)
        }

    @Test
    fun `joins the running transaction even when the pool is not equal to itself, like a CDI client proxy`() =
        runBlocking<Unit> {
            // given
            val proxyLikePool =
                object : io.vertx.mutiny.sqlclient.Pool(Database.pool.delegate) {
                    override fun equals(other: Any?): Boolean = false

                    override fun hashCode(): Int = 1
                }

            val proxied = VertxTransactionalExecutor(proxyLikePool, listeners = listOf(recorder))

            // when
            proxied.transaction {
                proxied.ambientQueryExecutor().insertGame("outer")
                proxied.withTransaction { it.insertGame("nested") }
            }

            // then
            assertThat(operations()).containsExactly(
                QueryOperation.BEGIN,
                QueryOperation.INSERT,
                QueryOperation.SAVEPOINT,
                QueryOperation.INSERT,
                QueryOperation.RELEASE_SAVEPOINT,
                QueryOperation.COMMIT,
            )
        }

    @Test
    fun `an exception escaping the unit of work rolls back everything`() =
        runBlocking<Unit> {
            // given
            val failure = IllegalStateException("force a rollback")

            // when
            val thrown =
                runCatching {
                    transactional.transaction {
                        ambient.insertGame("first")
                        transactional.withTransaction { it.insertGame("nested") }

                        throw failure
                    }
                }.exceptionOrNull()

            // then: the same failure (coroutines may hand back a copy with a recovered stack trace)
            assertThat(
                thrown,
            ).isInstanceOf(IllegalStateException::class.java).hasMessage(failure.message)
            assertThat(committedGames()).isEmpty()
            assertThat(operations().last()).isEqualTo(QueryOperation.ROLLBACK)
        }

    @Test
    fun `a failed nested call only undoes its own savepoint, so the caller can carry on`() =
        runBlocking<Unit> {
            // given
            var nestedFailure: Throwable? = null

            // when
            transactional.transaction {
                ambient.insertGame("kept")
                nestedFailure =
                    runCatching {
                        transactional.withTransaction {
                            it.insertGame(
                                null,
                            )
                        }
                    }.exceptionOrNull()
                ambient.insertGame("also kept")
            }

            // then
            assertThat(nestedFailure).hasMessageContaining("null value")
            assertThat(committedGames()).containsExactly("kept", "also kept")
            assertThat(operations()).contains(QueryOperation.ROLLBACK_TO_SAVEPOINT)
            assertThat(
                recorder.executions().single { it.failed }.operation,
            ).isEqualTo(QueryOperation.INSERT)
        }

    @Test
    fun `REQUIRES_NEW commits on its own connection, whatever the outer transaction does`() =
        runBlocking<Unit> {
            // given
            val requiresNew = TransactionOptions(propagation = TransactionPropagation.REQUIRES_NEW)

            // when
            runCatching {
                transactional.transaction {
                    ambient.insertGame("rolled back")
                    transactional.transaction(requiresNew) { ambient.insertGame("independent") }

                    error("force a rollback")
                }
            }

            // then
            assertThat(committedGames()).containsExactly("independent")
        }

    @Test
    fun `reads through the ambient executor see the unit of work's own uncommitted writes`() =
        runBlocking<Unit> {
            // given
            val count = "SELECT count(*) AS count FROM game"

            // when
            val seen =
                transactional.transaction {
                    ambient.insertGame("uncommitted")
                    checkNotNull(ambient.queryFirst(count)).long("count")
                }

            // then
            assertThat(seen).isEqualTo(1)
        }

    @Test
    fun `options set the isolation level and read-only mode of a new transaction`() =
        runBlocking<Unit> {
            // given
            val options =
                TransactionOptions(isolation = TransactionIsolation.SERIALIZABLE, readOnly = true)
            val settings =
                "SELECT current_setting('transaction_isolation') AS isolation, " +
                    "current_setting('transaction_read_only') AS read_only"

            // when
            val row =
                transactional.transaction(
                    options,
                ) { checkNotNull(ambient.queryFirst(settings)) }

            // then
            assertThat(row.string("isolation")).isEqualTo("serializable")
            assertThat(row.string("read_only")).isEqualTo("on")
        }

    @Test
    fun `a write inside a read-only transaction is rejected by the database`() {
        // given
        val readOnly = TransactionOptions(readOnly = true)

        // when
        val thrown =
            catchThrowable {
                runBlocking { transactional.transaction(readOnly) { ambient.insertGame("no") } }
            }

        // then
        assertThat(thrown).hasMessageContaining("read-only")
    }

    @Test
    fun `a read-only transaction cannot be joined to a running read-write one`() {
        // given
        val readOnly = TransactionOptions(readOnly = true)

        // when
        val thrown =
            catchThrowable {
                runBlocking {
                    transactional.transaction {
                        transactional.transaction(
                            readOnly,
                        ) { }
                    }
                }
            }

        // then
        assertThat(thrown)
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("read-only")
    }

    @Test
    fun `a listener that throws changes nothing about the outcome`() =
        runBlocking<Unit> {
            // given
            val failing =
                object : QueryExecutionListener {
                    override suspend fun executed(execution: QueryExecution) =
                        throw IllegalStateException("listener bug")
                }

            val withFailingListener =
                VertxTransactionalExecutor(Database.pool, listeners = listOf(failing))

            // when
            withFailingListener.transaction {
                withFailingListener.ambientQueryExecutor().insertGame("committed")
            }

            // then
            assertThat(committedGames()).containsExactly("committed")
        }

    @Test
    fun `cancelling the caller rolls back before the connection returns to the pool`() =
        runBlocking<Unit> {
            // given
            val inserted = CompletableDeferred<Unit>()
            val job =
                launch {
                    transactional.transaction {
                        ambient.insertGame("cancelled")
                        inserted.complete(Unit)
                        delay(60_000)
                    }
                }

            inserted.await()

            // when
            job.cancelAndJoin()

            // then: no committed row, and every pooled connection is free of an open transaction
            assertThat(committedGames()).isEmpty()
            assertThat(operations().last()).isEqualTo(QueryOperation.ROLLBACK)
            val sessions =
                (1..Database.POOL_SIZE)
                    .map {
                        async {
                            transactional.withTransaction { tx ->
                                checkNotNull(
                                    tx.queryFirst("SELECT count(*) AS n FROM game"),
                                ).long("n")
                            }
                        }
                    }.awaitAll()
            assertThat(sessions).containsOnly(0L)
            assertThat(
                Database.count(
                    "SELECT count(*) FROM pg_stat_activity WHERE state = 'idle in transaction'",
                ),
            ).isZero()
        }

    @Test
    fun `using one transaction from two coroutines at once is rejected`() {
        // given
        val slowQuery = "SELECT pg_sleep(0.3)"

        // when
        val thrown =
            catchThrowable {
                runBlocking {
                    transactional.withTransaction { tx ->
                        coroutineScope {
                            listOf(
                                async { tx.query(slowQuery) },
                                async { tx.query(slowQuery) },
                            ).awaitAll()
                        }
                    }
                }
            }

        // then
        assertThat(thrown)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("several coroutines at once")
    }

    @Test
    fun `a coroutine that outlives its transaction fails fast`() {
        // given
        var leaked: CoroutineContext? = null
        runBlocking {
            transactional.transaction {
                leaked =
                    currentCoroutineContext().minusKey(
                        Job,
                    )
            }
        }

        // when
        val thrown =
            catchThrowable { runBlocking(checkNotNull(leaked)) { ambient.insertGame("late") } }

        // then
        assertThat(thrown)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("already completed")
    }

    @Test
    fun `createAll inside a unit of work falls back per item without aborting the transaction`() =
        runBlocking<Unit> {
            // given
            val store = PlaysetSqlStore()
            val owner = Seed.user("Ada")
            val commands =
                listOf(
                    CreatePlayset("good", owner),
                    CreatePlayset("bad", owner, gameId = UUID.randomUUID()),
                )

            // when
            val result = store.transactional.transaction { store.create.createAll(commands) }

            // then
            assertThat(result.items[0]).isInstanceOf(BatchItemResult.Success::class.java)
            assertThat(result.items[1]).isInstanceOf(BatchItemResult.Failure::class.java)
            assertThat(
                Database.query("SELECT name FROM playset").map { it["name"] },
            ).containsExactly("good")
        }

    @Test
    fun `orThrow inside a unit of work makes a best-effort batch all-or-nothing`() {
        // given
        val store = PlaysetSqlStore()
        val owner = Seed.user("Ada")
        val commands =
            listOf(
                CreatePlayset("good", owner),
                CreatePlayset("bad", owner, gameId = UUID.randomUUID()),
            )

        // when
        val thrown =
            catchThrowable {
                runBlocking {
                    store.transactional.transaction {
                        store.create
                            .createAll(
                                commands,
                            ).orThrow()
                    }
                }
            }

        // then
        assertThat(thrown).isInstanceOf(BatchFailedException::class.java)
        assertThat(Database.count("SELECT count(*) FROM playset")).isZero()
    }
}
