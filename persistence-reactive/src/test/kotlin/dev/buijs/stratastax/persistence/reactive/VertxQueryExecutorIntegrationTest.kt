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

import dev.buijs.stratastax.ambient.context.api.PropagatedContextElement
import dev.buijs.stratastax.ambient.context.api.SynchronousContextElement
import dev.buijs.stratastax.ambient.context.api.currentPropagatedContext
import dev.buijs.stratastax.persistence.core.api.QueryExecution
import dev.buijs.stratastax.persistence.core.api.QueryOperation
import dev.buijs.stratastax.persistence.core.spi.QueryExecutionListener
import dev.buijs.stratastax.persistence.reactive.api.VertxQueryExecutor
import dev.buijs.stratastax.persistence.reactive.api.VertxTransactionalExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import kotlin.coroutines.CoroutineContext

private class TestPrincipalElement(
    val value: String,
) : PropagatedContextElement {

    companion object Key : CoroutineContext.Key<TestPrincipalElement>

    override val key: CoroutineContext.Key<TestPrincipalElement>
        get() = Key
}

private val testSyncPrincipalThreadLocal = ThreadLocal<String?>()

private class TestSyncPrincipalElement(
    value: String,
) : SynchronousContextElement<String?>(testSyncPrincipalThreadLocal, value) {

    companion object Key : CoroutineContext.Key<TestSyncPrincipalElement>

    override val key: CoroutineContext.Key<TestSyncPrincipalElement>
        get() = Key
}

/**
 * A plain, non-suspend function - mirrors `TypeGenerator.generate()` being called from a generated
 * `CreateMapper.map()`.
 */
private fun readSyncPrincipal(): String? = testSyncPrincipalThreadLocal.get()

private object PLAYSET : org.jooq.impl.TableImpl<org.jooq.Record>(DSL.name("playset")) {

    val ID: org.jooq.TableField<org.jooq.Record, Long> =
        createField(DSL.name("id"), org.jooq.impl.SQLDataType.BIGINT)
    val NAME: org.jooq.TableField<org.jooq.Record, String> =
        createField(DSL.name("name"), org.jooq.impl.SQLDataType.VARCHAR)
    val VERSION: org.jooq.TableField<org.jooq.Record, Long> =
        createField(DSL.name("version"), org.jooq.impl.SQLDataType.BIGINT)
    val ATTRIBUTES: org.jooq.TableField<org.jooq.Record, org.jooq.JSONB?> =
        createField(DSL.name("attributes"), org.jooq.impl.SQLDataType.JSONB)
}

/**
 * Exercises [VertxQueryExecutor]/[VertxTransactionalExecutor] against a real Postgres instance (via
 * Testcontainers) - [JooqAccessTest][dev.buijs.stratastax.persistence.reactive.JooqAccessTest] in
 * `persistence-jooq` already covers the query-building logic with a fake executor; this test is
 * specifically about whether jOOQ-built `Query` objects actually execute correctly through the real
 * Vert.x reactive Postgres client (placeholder rewriting, `RETURNING *` decoding, transaction
 * commit/rollback).
 *
 * **Requires Docker.** [setUp] calls [Assumptions.assumeTrue] before touching Testcontainers at all
 * (not the `@Testcontainers`/`@Container` annotations - those start the container as part of
 * JUnit's own extension lifecycle, *before* any `@BeforeAll` method runs, which throws a hard
 * `IllegalStateException` rather than a skippable assumption failure when no Docker daemon is
 * reachable), so this class is reported as *skipped*, not *failed*, in an environment without
 * Docker - `./gradlew build` stays green either way.
 */
internal class VertxQueryExecutorIntegrationTest {

    companion object {

        private lateinit var postgres: PostgreSQLContainer<*>
        private lateinit var pool: io.vertx.mutiny.sqlclient.Pool
        private val dsl = DSL.using(SQLDialect.POSTGRES)

        @BeforeAll
        @JvmStatic
        fun setUp() {
            val dockerAvailable =
                runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
            Assumptions.assumeTrue(dockerAvailable) {
                "Docker is not available - skipping VertxQueryExecutorIntegrationTest"
            }

            postgres = PostgreSQLContainer("postgres:16-alpine")
            postgres.start()

            DriverManager
                .getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
                .use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            """
                            CREATE TABLE playset (
                                id BIGSERIAL PRIMARY KEY,
                                name VARCHAR(255) NOT NULL,
                                version BIGINT NOT NULL DEFAULT 0,
                                attributes JSONB
                            )
                            """.trimIndent(),
                        )
                    }
                }

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
                    .with(io.vertx.sqlclient.PoolOptions())
                    .build()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            // setUp may have aborted (via assumeTrue) before initializing
            // either of these, when Docker isn't available.
            if (::pool.isInitialized) pool.close().await().indefinitely()
            if (::postgres.isInitialized) postgres.stop()
        }
    }

    private val executor = VertxQueryExecutor(pool)

    @Test
    fun `insert with RETURNING maps back to a row`() {
        runBlocking {
            val insert = dsl.insertInto(PLAYSET).set(mapOf(PLAYSET.NAME to "Catan")).returning()

            val row = executor.executeReturning(insert)

            assertThat(row?.string("name")).isEqualTo("Catan")
            assertThat(row?.long("version")).isEqualTo(0L)
        }
    }

    @Test
    fun `a JSONB bind value round-trips correctly - the actual write-path fix this test suite exists to catch`() {
        runBlocking {
            // Mirrors exactly how generated CreateMapper code now binds a
            // Map<String, Any?>-typed property - see
            // CreateUpdateBindingUnwrapResolver's own generated
            // ".let {
            // org.jooq.JSONB.valueOf(io.vertx.core.json.JsonObject(it).encode()) }"
            // suffix. Before VertxQueryExecutor's own bind-value unwrap fix,
            // this failed with "Parameter ... class = [org.jooq.JSON] ...
            // can not be coerced" - Vert.x's PG encoder has no codec for
            // jOOQ's own JSON/JSONB wrapper type, regardless of its content.
            val attributes = mapOf("color" to "red", "level" to 3)
            val jsonb =
                org.jooq.JSONB.valueOf(
                    io.vertx.core.json
                        .JsonObject(attributes)
                        .encode(),
                )
            val insert =
                dsl
                    .insertInto(PLAYSET)
                    .set(mapOf(PLAYSET.NAME to "Catan (JSONB)", PLAYSET.ATTRIBUTES to jsonb))
                    .returning()

            val row = executor.executeReturning(insert)

            assertThat(row?.map("attributes")).isEqualTo(mapOf("color" to "red", "level" to 3))
        }
    }

    @Test
    fun `a null JSONB bind value round-trips as null`() {
        runBlocking {
            val insert =
                dsl
                    .insertInto(PLAYSET)
                    .set(mapOf(PLAYSET.NAME to "Catan (no attributes)"))
                    .returning()

            val row = executor.executeReturning(insert)

            assertThat(row?.mapOrNull("attributes")).isNull()
        }
    }

    @Test
    fun `select finds the inserted row by id`() {
        runBlocking {
            val inserted =
                executor.executeReturning(
                    dsl.insertInto(PLAYSET).set(mapOf(PLAYSET.NAME to "Wingspan")).returning(),
                )
            val id = inserted!!.long("id")

            val select =
                dsl
                    .select(PLAYSET.ID, PLAYSET.NAME, PLAYSET.VERSION)
                    .from(PLAYSET)
                    .where(PLAYSET.ID.eq(id))
            val found = executor.executeAndGetFirst(select)

            assertThat(found?.string("name")).isEqualTo("Wingspan")
        }
    }

    @Test
    fun `update with a stale version affects zero rows`() {
        runBlocking {
            val inserted =
                executor.executeReturning(
                    dsl.insertInto(PLAYSET).set(mapOf(PLAYSET.NAME to "Azul")).returning(),
                )
            val id = inserted!!.long("id")

            val update =
                dsl
                    .update(PLAYSET)
                    .set(PLAYSET.NAME, "Azul (renamed)")
                    .where(PLAYSET.ID.eq(id).and(PLAYSET.VERSION.eq(999L)))
                    .returning()

            assertThat(executor.executeReturning(update)).isNull()
        }
    }

    @Test
    fun `delete affects exactly one row`() {
        runBlocking {
            val inserted =
                executor.executeReturning(
                    dsl.insertInto(PLAYSET).set(mapOf(PLAYSET.NAME to "Splendor")).returning(),
                )
            val id = inserted!!.long("id")

            val affected = executor.executeUpdate(dsl.deleteFrom(PLAYSET).where(PLAYSET.ID.eq(id)))

            assertThat(affected).isEqualTo(1)
            assertThat(
                executor.executeAndGetFirst(dsl.select().from(PLAYSET).where(PLAYSET.ID.eq(id))),
            ).isNull()
        }
    }

    @Test
    fun `a rolled-back transaction leaves no trace`() {
        runBlocking {
            val transactional = VertxTransactionalExecutor(pool)
            val marker = "should-not-exist-${System.nanoTime()}"

            runCatching {
                transactional.withTransaction { txExecutor ->
                    txExecutor.executeReturning(
                        dsl.insertInto(PLAYSET).set(mapOf(PLAYSET.NAME to marker)).returning(),
                    )
                    error("force a rollback")
                }
            }

            val found =
                executor.executeAndGetFirst(
                    dsl.select().from(PLAYSET).where(PLAYSET.NAME.eq(marker)),
                )
            assertThat(found).isNull()
        }
    }

    @Test
    fun `a query execution listener observes exactly one select`() {
        runBlocking {
            val observed = mutableListOf<QueryExecution>()
            val listener =
                object : QueryExecutionListener {

                    override suspend fun executed(execution: QueryExecution) {
                        observed += execution
                    }
                }

            val observedExecutor = VertxQueryExecutor(pool, listeners = listOf(listener))

            observedExecutor.executeAndGetFirst(
                dsl.select(PLAYSET.ID).from(PLAYSET).where(PLAYSET.ID.eq(-1L)),
            )

            assertThat(observed.size).isEqualTo(1)
            assertThat(observed.single().operation).isEqualTo(QueryOperation.SELECT)
        }
    }

    @Test
    fun `a committed transaction is visible afterwards`() {
        runBlocking {
            val transactional = VertxTransactionalExecutor(pool)
            val marker = "should-exist-${System.nanoTime()}"

            transactional.withTransaction { txExecutor ->
                txExecutor.executeReturning(
                    dsl.insertInto(PLAYSET).set(mapOf(PLAYSET.NAME to marker)).returning(),
                )
            }

            val found =
                executor.executeAndGetFirst(
                    dsl.select().from(PLAYSET).where(PLAYSET.NAME.eq(marker)),
                )
            assertThat(found).isNotNull()
        }
    }

    /**
     * Proves the actual bug this ambient-context work exists to fix: `Pool.withTransaction {
     * connection -> ... }`'s callback is a plain Vert.x reactive-pool callback, not a coroutine
     * resumption - before `VertxTransactionalExecutor.withTransaction` explicitly captured and
     * re-passed the ambient context across that boundary, whatever a caller installed here would
     * have silently reverted to absent inside the transaction block. Runs against the real
     * Testcontainers [Pool], not a fake, since the bug only reproduces across a genuine
     * reactive-callback boundary.
     *
     * Reads via [currentPropagatedContext] rather than a bare
     * `coroutineContext[TestPrincipalElement]` - see `ambient-context`'s own `InheritedUniTest` for
     * why a bare read inside a `withTransaction` `block` lambda is unreliable here regardless of
     * this bug.
     */
    @Test
    fun `ambient context installed before withTransaction survives the pool's reactive callback boundary`() {
        runBlocking {
            val transactional = VertxTransactionalExecutor(pool)

            val seen =
                withContext(TestPrincipalElement("user-1")) {
                    transactional.withTransaction {
                        currentPropagatedContext()[TestPrincipalElement]?.value
                    }
                }

            assertThat(seen).isEqualTo("user-1")
        }
    }

    @Test
    fun `withTransaction sees no ambient context when none was installed`() {
        runBlocking {
            val transactional = VertxTransactionalExecutor(pool)

            val seen =
                transactional.withTransaction {
                    currentPropagatedContext()[TestPrincipalElement]?.value
                }

            assertThat(seen).isNull()
        }
    }

    /**
     * Mirrors the exact real-world shape a generated `CreateMapper.map()` calling a
     * `TypeGenerator.generate()` has: a plain, non-suspend function ([readSyncPrincipal]) called
     * synchronously from suspend code ([VertxTransactionalExecutor.withTransaction]'s `block`),
     * itself crossing the same `Pool.withTransaction` reactive-callback boundary as the test above.
     * Proves a [SynchronousContextElement] resolves correctly end to end in exactly that shape,
     * against the real Testcontainers [Pool].
     */
    @Test
    fun `a SynchronousContextElement is readable from plain sync code called from inside withTransaction`() {
        runBlocking {
            val transactional = VertxTransactionalExecutor(pool)

            val seen =
                withContext(TestSyncPrincipalElement("user-1")) {
                    transactional.withTransaction { readSyncPrincipal() }
                }

            assertThat(seen).isEqualTo("user-1")
        }
    }
}
