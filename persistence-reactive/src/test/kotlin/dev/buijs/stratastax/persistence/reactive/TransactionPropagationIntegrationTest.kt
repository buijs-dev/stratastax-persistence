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
package dev.buijs.stratastax.persistence.reactive

import dev.buijs.stratastax.ambient.context.api.currentPropagatedContext
import dev.buijs.stratastax.entity.core.api.EntityField
import dev.buijs.stratastax.entity.core.api.EntityFieldType
import dev.buijs.stratastax.persistence.core.api.BatchFailedException
import dev.buijs.stratastax.persistence.core.api.OptimisticLockException
import dev.buijs.stratastax.persistence.core.api.QueryExecution
import dev.buijs.stratastax.persistence.core.api.QueryOperation
import dev.buijs.stratastax.persistence.core.api.TransactionIsolation
import dev.buijs.stratastax.persistence.core.api.TransactionOptions
import dev.buijs.stratastax.persistence.core.api.TransactionPropagation
import dev.buijs.stratastax.persistence.core.spi.CreateMapper
import dev.buijs.stratastax.persistence.core.spi.QueryExecutionListener
import dev.buijs.stratastax.persistence.core.spi.RowContext
import dev.buijs.stratastax.persistence.core.spi.RowMapper
import dev.buijs.stratastax.persistence.core.spi.orThrow
import dev.buijs.stratastax.persistence.reactive.api.JooqCreateAccess
import dev.buijs.stratastax.persistence.reactive.api.JooqDeleteAccess
import dev.buijs.stratastax.persistence.reactive.api.JooqField
import dev.buijs.stratastax.persistence.reactive.api.JooqFieldMapping
import dev.buijs.stratastax.persistence.reactive.api.JooqReadAccess
import dev.buijs.stratastax.persistence.reactive.api.JooqSearchQueryTranslator
import dev.buijs.stratastax.persistence.reactive.api.QueryExecutor
import dev.buijs.stratastax.persistence.reactive.api.VertxQueryExecutor
import dev.buijs.stratastax.persistence.reactive.api.VertxTransactionalExecutor
import dev.buijs.stratastax.persistence.reactive.api.findAllForUpdate
import dev.buijs.stratastax.persistence.reactive.app.CreateValues
import dev.buijs.stratastax.persistence.reactive.app.JunctionDeleteWrite
import dev.buijs.stratastax.search.core.api.search
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.Record
import org.jooq.SQLDialect
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.impl.TableImpl
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import kotlin.coroutines.CoroutineContext

// Named like the jOOQ-generated table it stands in for.
@Suppress("ktlint:standard:class-naming")
private object TX_PLAYSET : TableImpl<Record>(DSL.name("tx_playset")) {

    val ID: TableField<Record, Long> =
        createField(DSL.name("id"), SQLDataType.BIGINT.nullable(false))
    val NAME: TableField<Record, String> = createField(DSL.name("name"), SQLDataType.VARCHAR)
    val VERSION: TableField<Record, Long> = createField(DSL.name("version"), SQLDataType.BIGINT)
}

// Named like the jOOQ-generated table it stands in for.
@Suppress("ktlint:standard:class-naming")
private object TX_PLAYSET_TAG : TableImpl<Record>(DSL.name("tx_playset_tag")) {

    val PLAYSET_ID: TableField<Record, Long> =
        createField(DSL.name("playset_id"), SQLDataType.BIGINT)
    val TAG: TableField<Record, String> = createField(DSL.name("tag"), SQLDataType.VARCHAR)
}

private data class TxPlayset(
    val id: Long,
    val name: String,
)

/**
 * Transaction propagation of [VertxTransactionalExecutor] - joining, savepoints, `REQUIRES_NEW`,
 * transaction options - and the access classes taking part in a caller's transaction, against a
 * real Postgres (a fake executor cannot show any of this: it is all about what the database
 * commits, rolls back and locks). Skipped without Docker, like [VertxQueryExecutorIntegrationTest].
 */
internal class TransactionPropagationIntegrationTest {

    companion object {

        private lateinit var postgres: PostgreSQLContainer<*>
        private lateinit var pool: io.vertx.mutiny.sqlclient.Pool

        @BeforeAll
        @JvmStatic
        fun setUp() {
            val dockerAvailable =
                runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
            Assumptions.assumeTrue(dockerAvailable) {
                "Docker is not available - skipping TransactionPropagationIntegrationTest"
            }

            postgres = PostgreSQLContainer("postgres:16-alpine")
            postgres.start()
            jdbc(
                """
                CREATE TABLE tx_playset (
                    id BIGSERIAL PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    version BIGINT NOT NULL DEFAULT 0
                );
                CREATE TABLE tx_playset_tag (
                    playset_id BIGINT NOT NULL,
                    tag VARCHAR(255) NOT NULL
                );
                """,
            )

            val connectOptions =
                io.vertx.pgclient
                    .PgConnectOptions()
                    .setHost(postgres.host)
                    .setPort(postgres.firstMappedPort)
                    .setDatabase(postgres.databaseName)
                    .setUser(postgres.username)
                    .setPassword(postgres.password)

            pool =
                io.vertx.mutiny.pgclient.PgBuilder
                    .pool()
                    .connectingTo(connectOptions)
                    .with(
                        io.vertx.sqlclient
                            .PoolOptions()
                            .setMaxSize(4),
                    ).build()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            if (::pool.isInitialized) pool.close().await().indefinitely()
            if (::postgres.isInitialized) postgres.stop()
        }

        private fun jdbc(sql: String) {
            DriverManager
                .getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
                .use { connection -> connection.createStatement().use { it.execute(sql) } }
        }
    }

    private val dsl = DSL.using(SQLDialect.POSTGRES)

    /** Every statement the transactional/ambient executors run, transaction control included. */
    private val observed = java.util.Collections.synchronizedList(mutableListOf<QueryExecution>())
    private val recorder =
        object : QueryExecutionListener {

            override suspend fun executed(execution: QueryExecution) {
                observed += execution
            }
        }

    private val transactional = VertxTransactionalExecutor(pool, listeners = listOf(recorder))
    private val ambient = transactional.ambientQueryExecutor()

    /** Its own connection, outside any transaction - what every other session sees. */
    private val outside = VertxQueryExecutor(pool)

    private val idField =
        EntityField(name = "id", type = EntityFieldType.NUMBER, sortable = true, filterable = true)

    private val txPlaysetMapper =
        RowMapper { row ->
            TxPlayset(id = row.long("id"), name = row.string("name"))
        }

    private val txPlaysetMapping =
        JooqFieldMapping(
            fields =
                mapOf(
                    "id" to JooqField(field = TX_PLAYSET.ID, selectable = true),
                    "name" to JooqField(field = TX_PLAYSET.NAME, selectable = true),
                ),
        )

    private val readAccess =
        object : JooqReadAccess<Long, TxPlayset> {

            override val dsl = this@TransactionPropagationIntegrationTest.dsl
            override val table = TX_PLAYSET
            override val rowMapper = txPlaysetMapper
            override val fieldMapping = txPlaysetMapping
            override val queryExecutor = ambient
            override val queryTranslator = JooqSearchQueryTranslator()
            override val idField = this@TransactionPropagationIntegrationTest.idField
        }

    /** Creates a playset by name; a `null` name violates the NOT NULL constraint. */
    private val createAccess =
        object : JooqCreateAccess<String?, TxPlayset> {

            override val dsl = this@TransactionPropagationIntegrationTest.dsl
            override val table = TX_PLAYSET
            override val rowMapper = txPlaysetMapper
            override val queryExecutor = ambient
            override val createMapper =
                CreateMapper<String?, CreateValues> { name ->
                    CreateValues(TX_PLAYSET.NAME to name)
                }

            override val fieldMapping = txPlaysetMapping
            override val queryTranslator = JooqSearchQueryTranslator()
            override val idField = this@TransactionPropagationIntegrationTest.idField
            override val transactionalExecutor = transactional
            override val extractRootId: (RowContext) -> Any = { row -> row.long("id") }
        }

    private val deleteAccess =
        object : JooqDeleteAccess<Long> {

            override val dsl = this@TransactionPropagationIntegrationTest.dsl
            override val table = TX_PLAYSET
            override val queryExecutor = ambient
            override val transactionalExecutor = transactional
            override val idColumn = TX_PLAYSET.ID
            override val versionColumn = TX_PLAYSET.VERSION
            override val deleteJunctionWrites =
                listOf(
                    JunctionDeleteWrite(TX_PLAYSET_TAG, TX_PLAYSET_TAG.PLAYSET_ID, TX_PLAYSET.ID),
                )
        }

    @BeforeEach
    fun clean() {
        jdbc("TRUNCATE tx_playset, tx_playset_tag RESTART IDENTITY")
    }

    private suspend fun QueryExecutor.insertPlayset(
        name: String?,
        version: Long = 0,
    ): Long =
        executeReturning(
            dsl
                .insertInto(TX_PLAYSET)
                .set(TX_PLAYSET.NAME, name)
                .set(TX_PLAYSET.VERSION, version)
                .returning(TX_PLAYSET.ID),
        )!!
            .long("id")

    private suspend fun QueryExecutor.tag(
        playsetId: Long,
        tag: String,
    ) {
        executeUpdate(
            dsl
                .insertInto(TX_PLAYSET_TAG)
                .set(TX_PLAYSET_TAG.PLAYSET_ID, playsetId)
                .set(TX_PLAYSET_TAG.TAG, tag),
        )
    }

    private suspend fun QueryExecutor.names(): List<String> =
        executeAndGetAll(dsl.select(TX_PLAYSET.NAME).from(TX_PLAYSET).orderBy(TX_PLAYSET.ID)).map {
            it.string("name")
        }

    private suspend fun QueryExecutor.tagCount(): Long =
        executeAndGetCount(dsl.selectCount().from(TX_PLAYSET_TAG))

    private suspend fun QueryExecutor.txid(): Long =
        executeAndGetFirst(dsl.resultQuery("select txid_current() as txid"))!!.long("txid")

    @Test
    fun `a listener observes a unit of work's complete statement sequence under one transaction id`() {
        runBlocking {
            transactional.transaction {
                ambient.insertPlayset("outer")
                transactional.withTransaction { tx -> tx.insertPlayset("nested") }
            }

            assertThat(observed.map { it.operation })
                .containsExactly(
                    QueryOperation.BEGIN,
                    QueryOperation.INSERT,
                    QueryOperation.SAVEPOINT,
                    QueryOperation.INSERT,
                    QueryOperation.RELEASE_SAVEPOINT,
                    QueryOperation.COMMIT,
                )
            assertThat(observed.map { it.transactionId }.distinct()).hasSize(1).doesNotContainNull()
            assertThat(observed).allMatch { !it.failed }
        }
    }

    @Test
    fun `a failed statement is reported with its error, followed by the savepoint rollback`() {
        runBlocking {
            transactional.transaction {
                runCatching { transactional.withTransaction { tx -> tx.insertPlayset(null) } }
            }

            assertThat(observed.map { it.operation })
                .containsExactly(
                    QueryOperation.BEGIN,
                    QueryOperation.SAVEPOINT,
                    QueryOperation.INSERT,
                    QueryOperation.ROLLBACK_TO_SAVEPOINT,
                    QueryOperation.COMMIT,
                )
            val failedInsert = observed[2]
            assertThat(failedInsert.error).hasMessageContaining("null value")
            assertThat(failedInsert.rowCount).isNull()
        }
    }

    @Test
    fun `a unit of work that throws is reported as ending with ROLLBACK`() {
        runBlocking {
            runCatching {
                transactional.transaction {
                    ambient.insertPlayset("discarded")
                    error("force a rollback")
                }
            }

            assertThat(observed.map { it.operation })
                .containsExactly(
                    QueryOperation.BEGIN,
                    QueryOperation.INSERT,
                    QueryOperation.ROLLBACK,
                )
        }
    }

    @Test
    fun `a query outside any transaction is reported without a transaction id`() {
        runBlocking {
            ambient.names()

            assertThat(observed.single().operation).isEqualTo(QueryOperation.SELECT)
            assertThat(observed.single().transactionId).isNull()
        }
    }

    @Test
    fun `joins the running transaction even when the pool is not equal to itself, like a CDI client proxy`() {
        // An Arc client proxy delegates equals() to the proxied instance, whose class check rejects
        // the proxy - so `pool == pool` is false. Matching the owner by equals made every nested
        // call open its own transaction in a Quarkus application.
        val proxyLikePool =
            object : io.vertx.mutiny.sqlclient.Pool(pool.delegate) {

                override fun equals(other: Any?): Boolean = false

                override fun hashCode(): Int = 1
            }

        val proxied = VertxTransactionalExecutor(proxyLikePool, listeners = listOf(recorder))
        val proxiedAmbient = proxied.ambientQueryExecutor()

        runBlocking {
            proxied.transaction {
                proxiedAmbient.insertPlayset("outer")
                proxied.withTransaction { tx -> tx.insertPlayset("nested") }
            }
        }

        assertThat(observed.map { it.operation })
            .containsExactly(
                QueryOperation.BEGIN,
                QueryOperation.INSERT,
                QueryOperation.SAVEPOINT,
                QueryOperation.INSERT,
                QueryOperation.RELEASE_SAVEPOINT,
                QueryOperation.COMMIT,
            )
        assertThat(observed.map { it.transactionId }.distinct()).hasSize(1).doesNotContainNull()
    }

    @Test
    fun `a nested withTransaction joins the running transaction instead of opening a second one`() {
        runBlocking {
            val (outer, nested) =
                transactional.withTransaction { tx ->
                    tx.txid() to transactional.withTransaction { nestedTx -> nestedTx.txid() }
                }

            assertThat(nested).isEqualTo(outer)
        }
    }

    @Test
    fun `an exception escaping the unit of work rolls back every write made inside it`() {
        runBlocking {
            runCatching {
                transactional.transaction {
                    ambient.insertPlayset("first")
                    transactional.withTransaction { tx -> tx.insertPlayset("nested") }

                    error("force a rollback")
                }
            }

            assertThat(outside.names()).isEmpty()
        }
    }

    @Test
    fun `a failed nested call only rolls back its own savepoint, so the caller can catch it and carry on`() {
        runBlocking {
            transactional.transaction {
                ambient.insertPlayset("before")
                // NOT NULL violation: without a savepoint Postgres would now reject every further
                // statement of this transaction ("current transaction is aborted").
                val failure =
                    runCatching {
                        transactional.withTransaction { tx ->
                            tx.insertPlayset("inside-savepoint")
                            tx.insertPlayset(null)
                        }
                    }

                assertThat(failure.isFailure).isTrue()
                ambient.insertPlayset("after")
            }

            assertThat(outside.names()).containsExactly("before", "after")
        }
    }

    @Test
    fun `createAll inside a unit of work falls back per item after the batch insert fails, without aborting the transaction`() {
        runBlocking {
            val result =
                transactional.transaction { createAccess.createAll(listOf("Catan", null, "Azul")) }

            assertThat(result.successes.map { it.name }).containsExactly("Catan", "Azul")
            assertThat(result.failures).hasSize(1)
            assertThat(outside.names()).containsExactly("Catan", "Azul")
        }
    }

    @Test
    fun `orThrow inside a unit of work turns a best-effort batch into an all-or-nothing one`() {
        runBlocking {
            assertThatThrownBy {
                runBlocking {
                    transactional.transaction {
                        createAccess.createAll(listOf("Catan", null, "Azul")).orThrow()
                    }
                }
            }.isInstanceOf(BatchFailedException::class.java)

            assertThat(outside.names()).isEmpty()
        }
    }

    @Test
    fun `reads through the ambient executor see the unit of work's own uncommitted writes`() {
        runBlocking {
            transactional.transaction {
                ambient.insertPlayset("uncommitted")

                assertThat(ambient.names()).containsExactly("uncommitted")
                assertThat(outside.names()).isEmpty()
            }

            assertThat(outside.names()).containsExactly("uncommitted")
        }
    }

    @Test
    fun `REQUIRES_NEW commits on its own connection, independently of the outer rollback`() {
        runBlocking {
            runCatching {
                transactional.transaction {
                    ambient.insertPlayset("outer")
                    transactional.transaction(
                        TransactionOptions(propagation = TransactionPropagation.REQUIRES_NEW),
                    ) {
                        ambient.insertPlayset("independent")
                    }

                    error("force an outer rollback")
                }
            }

            assertThat(outside.names()).containsExactly("independent")
        }
    }

    @Test
    fun `transaction options set the isolation level and read-only mode of a new transaction`() {
        runBlocking {
            val settings =
                transactional.transaction(
                    TransactionOptions(
                        isolation = TransactionIsolation.SERIALIZABLE,
                        readOnly = true,
                    ),
                ) {
                    ambient.executeAndGetFirst(
                        dsl.resultQuery(
                            "select current_setting('transaction_isolation') as isolation, " +
                                "current_setting('transaction_read_only') as read_only",
                        ),
                    )!!
                }

            assertThat(settings.string("isolation")).isEqualTo("serializable")
            assertThat(settings.string("read_only")).isEqualTo("on")
        }
    }

    @Test
    fun `a write inside a read-only transaction is rejected`() {
        assertThatThrownBy {
            runBlocking {
                transactional.transaction(TransactionOptions(readOnly = true)) {
                    ambient.insertPlayset("not allowed")
                }
            }
        }.hasMessageContaining("read-only")
    }

    @Test
    fun `a delete rejected for a stale version leaves its cascades untouched`() {
        runBlocking {
            val id = outside.insertPlayset("Catan", version = 3)
            outside.tag(id, "family")
            outside.tag(id, "trading")

            assertThatThrownBy { runBlocking { deleteAccess.delete(id, expectedVersion = 2L) } }
                .isInstanceOf(OptimisticLockException::class.java)

            // Before the delete ran in a transaction, the junction rows were already deleted
            // (autocommit) by the time the stale version was detected.
            assertThat(outside.tagCount()).isEqualTo(2)
            assertThat(outside.names()).containsExactly("Catan")
        }
    }

    @Test
    fun `a delete inside a unit of work is rolled back together with it`() {
        runBlocking {
            val id = outside.insertPlayset("Catan", version = 3)
            outside.tag(id, "family")

            runCatching {
                transactional.transaction {
                    deleteAccess.delete(id, expectedVersion = 3L)
                    error("force a rollback")
                }
            }

            assertThat(outside.names()).containsExactly("Catan")
            assertThat(outside.tagCount()).isEqualTo(1)
        }
    }

    @Test
    fun `findAllForUpdate with skipLocked lets concurrent workers claim different rows`() {
        runBlocking {
            listOf("a", "b", "c", "d").forEach { outside.insertPlayset(it) }

            val nextTwo =
                search {
                    limit = 2
                    sort { idField.asc() }
                }

            val firstClaimed = CompletableDeferred<List<String>>()
            val releaseFirst = CompletableDeferred<Unit>()

            coroutineScope {
                val first =
                    async {
                        transactional.transaction {
                            val claimed = readAccess.findAllForUpdate(nextTwo).map { it.name }

                            firstClaimed.complete(claimed)
                            releaseFirst.await()
                        }
                    }

                val claimedByFirst = firstClaimed.await()
                val claimedBySecond =
                    transactional.transaction {
                        readAccess.findAllForUpdate(nextTwo).map { it.name }
                    }

                releaseFirst.complete(Unit)
                first.await()

                assertThat(claimedByFirst).containsExactly("a", "b")
                assertThat(claimedBySecond).containsExactly("c", "d")
            }
        }
    }

    @Test
    fun `findAllForUpdate outside a transaction is rejected`() {
        assertThatThrownBy {
            runBlocking {
                readAccess.findAllForUpdate(
                    search {
                        limit = 1
                        sort { idField.asc() }
                    },
                )
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("inside a transaction")
    }

    @Test
    fun `a coroutine that outlives its transaction fails fast instead of using a closed connection`() {
        var leaked: CoroutineContext? = null
        runBlocking { transactional.transaction { leaked = currentPropagatedContext() } }

        assertThatThrownBy { runBlocking(leaked!!) { ambient.names() } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("already completed")
    }
}
