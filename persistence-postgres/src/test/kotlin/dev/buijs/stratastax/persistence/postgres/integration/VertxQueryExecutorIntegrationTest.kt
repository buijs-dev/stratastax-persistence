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
package dev.buijs.stratastax.persistence.postgres.integration

import dev.buijs.stratastax.ambient.context.api.PropagatedContextElement
import dev.buijs.stratastax.ambient.context.api.SynchronousContextElement
import dev.buijs.stratastax.ambient.context.api.currentPropagatedContext
import dev.buijs.stratastax.entity.core.api.SortDirection
import dev.buijs.stratastax.persistence.core.spi.QueryOperation
import dev.buijs.stratastax.persistence.core.spi.UnknownColumnException
import dev.buijs.stratastax.persistence.postgres.api.PostgresDialect
import dev.buijs.stratastax.persistence.postgres.testfixtures.Database
import dev.buijs.stratastax.persistence.postgres.testfixtures.Seed
import dev.buijs.stratastax.persistence.reactive.api.VertxQueryExecutor
import dev.buijs.stratastax.persistence.reactive.api.VertxTransactionalExecutor
import dev.buijs.stratastax.persistence.reactive.spi.toSqlArray
import dev.buijs.stratastax.persistence.test.api.QueryExecutionRecorder
import io.vertx.core.json.JsonArray
import io.vertx.mutiny.sqlclient.Tuple
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.CoroutineContext

private class TestPrincipalElement(
    val value: String,
) : PropagatedContextElement {

    companion object Key : CoroutineContext.Key<TestPrincipalElement>

    override val key: CoroutineContext.Key<TestPrincipalElement>
        get() = Key
}

private val syncPrincipal = ThreadLocal<String?>()

private class TestSyncPrincipalElement(
    value: String,
) : SynchronousContextElement<String?>(syncPrincipal, value) {

    companion object Key : CoroutineContext.Key<TestSyncPrincipalElement>

    override val key: CoroutineContext.Key<TestSyncPrincipalElement>
        get() = Key
}

/** A plain, non-suspend function, like a type generator called from a generated create mapper. */
private fun readSyncPrincipal(): String? = syncPrincipal.get()

/**
 * [VertxQueryExecutor] against a real Postgres: numbered placeholders, bind values (JSON, arrays,
 * what the executor converts), result decoding, batches in one round trip and how each statement is
 * reported to the listeners - and that ambient context reaches a transaction's block.
 */
internal class VertxQueryExecutorIntegrationTest {

    companion object {

        @BeforeAll
        @JvmStatic
        fun start() = Database.start()

        private const val INSERT_GAME =
            "INSERT INTO game (public_id, name) VALUES (\$1::uuid, \$2::varchar) RETURNING name"
    }

    private val recorder = QueryExecutionRecorder()
    private val executor =
        VertxQueryExecutor(Database.pool, PostgresDialect, listeners = listOf(recorder))

    @BeforeEach
    fun clean() {
        Database.truncate()
    }

    /** The key of a new user to own playsets. */
    private fun owner(): Long {
        Seed.user("Ada")
        return Database.count("SELECT max(id) FROM app_user")
    }

    private suspend fun insertPlayset(
        owner: Long,
        attributes: Any?,
    ): UUID {
        val id = UUID.randomUUID()
        executor.execute(
            "INSERT INTO playset (public_id, name, owner_id, attributes) VALUES (\$1::uuid, 'x', \$2::bigint, \$3::jsonb)",
            listOf(id, owner, attributes),
        )
        return id
    }

    @Test
    fun `INSERT RETURNING hands back the new row`() =
        runBlocking<Unit> {
            // given
            val insert =
                "INSERT INTO playset (public_id, name, owner_id) VALUES (\$1::uuid, \$2::varchar, \$3::bigint) RETURNING id"

            // when
            val row = executor.queryFirst(insert, listOf(UUID.randomUUID(), "x", owner()))

            // then
            assertThat(row?.long("id")).isEqualTo(1L)
        }

    @Test
    fun `JSON objects, arrays, scalars and SQL NULL are bound to a jsonb column`() =
        runBlocking<Unit> {
            // given
            val values =
                listOf(
                    mapOf("players" to 4),
                    JsonArray().add(1).add(2),
                    "text",
                    42,
                    true,
                    Tuple.JSON_NULL,
                )
            val owner = owner()

            // when
            values.forEach { insertPlayset(owner, it) }

            insertPlayset(owner, null)

            // then
            assertThat(
                Database
                    .query(
                        "SELECT attributes::text AS a FROM playset ORDER BY id",
                    ).map { it["a"] },
            ).containsExactly(
                "{\"players\": 4}",
                "[1, 2]",
                "\"text\"",
                "42",
                "true",
                "null",
                null,
            )
            assertThat(
                Database
                    .query(
                        "SELECT jsonb_typeof(attributes) AS t FROM playset ORDER BY id",
                    ).map { it["t"] },
            ).containsExactly("object", "array", "string", "number", "boolean", "null", null)
        }

    @Test
    fun `many ids are bound as a single array parameter`() =
        runBlocking<Unit> {
            // given
            val owner = owner()
            val ids = (1..5).map { insertPlayset(owner, null) }

            recorder.clear()

            // when
            val rows =
                executor.query(
                    "SELECT public_id FROM playset WHERE public_id = ANY(\$1::uuid[])",
                    listOf(ids.toTypedArray()),
                )

            // then
            assertThat(rows.map { it.uuid("public_id") }).containsExactlyInAnyOrderElementsOf(ids)
            assertThat(
                recorder
                    .executions()
                    .single()
                    .query.parameters,
            ).hasSize(1)
        }

    @Test
    fun `a question mark in a literal is not mistaken for a placeholder`() =
        runBlocking<Unit> {
            // given
            val sql = "SELECT 'why?' AS q, \$1::varchar AS a"

            // when
            val row = checkNotNull(executor.queryFirst(sql, listOf("answer")))

            // then
            assertThat(row.string("q")).isEqualTo("why?")
            assertThat(row.string("a")).isEqualTo("answer")
        }

    @Test
    fun `reading a column the result does not have throws`() =
        runBlocking<Unit> {
            // given
            Seed.user("Ada")
            val row = checkNotNull(executor.queryFirst("SELECT display_name FROM app_user"))

            // when
            val thrown = catchThrowable { row.stringOrNull("name") }

            // then
            assertThat(thrown).isInstanceOf(UnknownColumnException::class.java)
            assertThat(row.string("display_name")).isEqualTo("Ada")
        }

    @Test
    fun `every statement is reported with its operation and row count`() =
        runBlocking<Unit> {
            // given
            Seed.user("Ada")

            // when
            executor.query("SELECT * FROM app_user")

            // then
            val execution = recorder.executions().single()
            assertThat(execution.operation).isEqualTo(QueryOperation.SELECT)
            assertThat(execution.rowCount).isEqualTo(1)
            assertThat(execution.failed).isFalse()
        }

    @Test
    fun `query binds the parameters to the numbered placeholders and reads the rows by alias`() =
        runBlocking<Unit> {
            // given
            val sql = "SELECT \$1::text AS greeting, \$2::bigint AS answer, \$3::uuid AS nothing"

            // when
            val row = executor.query(sql, listOf("hello", 42L, null)).single()

            // then
            assertThat(row.string("greeting")).isEqualTo("hello")
            assertThat(row.long("answer")).isEqualTo(42L)
            assertThat(row.uuidOrNull("nothing")).isNull()
        }

    @Test
    fun `a map is bound as a JSON object and a typed array as a SQL array`() =
        runBlocking<Unit> {
            // given
            val ids = arrayOf(UUID(0, 1), UUID(0, 2), UUID(0, 3))

            // when
            val json =
                executor.queryFirst(
                    "SELECT \$1::jsonb AS doc",
                    listOf(
                        mapOf(
                            "players" to 4,
                            "tags" to listOf("family"),
                        ),
                    ),
                )
            val count =
                executor.queryFirst(
                    "SELECT count(*) AS n FROM unnest(\$1::uuid[])",
                    listOf(ids),
                )

            // then
            assertThat(
                json?.map("doc"),
            ).isEqualTo(mapOf("players" to 4, "tags" to listOf("family")))
            assertThat(count?.long("n")).isEqualTo(3L)
        }

    @Test
    fun `a collection bound with toSqlArray is a SQL array of its type, also when empty`() =
        runBlocking<Unit> {
            // given
            val variants = setOf("base", "legacy")
            val none = emptyList<String>()

            // when
            val filled =
                executor.queryFirst(
                    "SELECT cardinality(\$1::text[]) AS n",
                    listOf(variants.toSqlArray()),
                )
            val empty =
                executor.queryFirst(
                    "SELECT cardinality(\$1::text[]) AS n",
                    listOf(none.toSqlArray()),
                )

            // then
            assertThat(filled?.integer("n")).isEqualTo(2)
            assertThat(empty?.integer("n")).isEqualTo(0)
        }

    @Test
    fun `a URI, an enum and an Instant are bound the way their text and timestamp columns hold them`() =
        runBlocking<Unit> {
            // given
            val image = URI("https://example.com/catan.jpg")
            val played = Instant.parse("2026-10-19T10:15:30Z")

            // when
            val row =
                executor.queryFirst(
                    "SELECT \$1::varchar AS image, \$2::varchar AS direction, \$3::timestamptz AS played",
                    listOf(image, SortDirection.DESC, played),
                )

            // then
            assertThat(row?.string("image")).isEqualTo("https://example.com/catan.jpg")
            assertThat(row?.string("direction")).isEqualTo("DESC")
            assertThat(row?.offsetDateTime("played")?.toInstant()).isEqualTo(played)
        }

    @Test
    fun `execute returns the number of rows a statement affected`() =
        runBlocking<Unit> {
            // given
            executor.execute(INSERT_GAME, listOf(UUID.randomUUID(), "Catan"))
            executor.execute(INSERT_GAME, listOf(UUID.randomUUID(), "Azul"))

            // when
            val renamed = executor.execute("UPDATE game SET name = name || '!'")

            // then
            assertThat(renamed).isEqualTo(2)
            assertThat(
                Database.count("SELECT count(*) FROM game WHERE name LIKE '%!'"),
            ).isEqualTo(2L)
        }

    @Test
    fun `batch runs every parameter set in one statement and returns the rows of each in order`() =
        runBlocking<Unit> {
            // given
            val games = listOf("Catan", "Azul", "Brass").map { listOf(UUID.randomUUID(), it) }

            // when
            val inserted = executor.batch(INSERT_GAME, games)

            // then
            assertThat(
                inserted.map { rows -> rows.map { it.string("name") } },
            ).containsExactly(listOf("Catan"), listOf("Azul"), listOf("Brass"))
            val execution = recorder.executions().single()
            assertThat(execution.batchSize).isEqualTo(3)
            assertThat(execution.rowCount).isEqualTo(3)
            assertThat(execution.operation).isEqualTo(QueryOperation.INSERT)
            assertThat(execution.query.parameters).isEqualTo(games.first())
        }

    @Test
    fun `a batch run without RETURNING, or that matches nothing, has no rows of its own`() =
        runBlocking<Unit> {
            // given
            val catan = UUID.randomUUID()
            executor.execute(INSERT_GAME, listOf(catan, "Catan"))
            recorder.clear()

            // when
            val updated =
                executor.batch(
                    "UPDATE game SET name = \$2::varchar WHERE public_id = \$1::uuid",
                    listOf(listOf(catan, "Catan!"), listOf(UUID.randomUUID(), "missing")),
                )

            // then
            assertThat(updated).containsExactly(emptyList(), emptyList())
            assertThat(recorder.executions().single().rowCount).isEqualTo(1)
        }

    @Test
    fun `an empty batch runs nothing`() =
        runBlocking<Unit> {
            // given
            val batch = emptyList<List<Any?>>()

            // when
            val results = executor.batch(INSERT_GAME, batch)

            // then
            assertThat(results).isEmpty()
            assertThat(recorder.executions()).isEmpty()
        }

    @Test
    fun `a failing batch is reported once, with its error`() =
        runBlocking<Unit> {
            // given
            val id = UUID.randomUUID()

            // when
            val thrown =
                catchThrowable {
                    runBlocking {
                        executor.batch(
                            INSERT_GAME,
                            listOf(listOf(id, "a"), listOf(id, "b")),
                        )
                    }
                }

            // then
            assertThat(thrown).hasMessageContaining("duplicate key")
            val execution = recorder.executions().single()
            assertThat(execution.failed).isTrue()
            assertThat(execution.batchSize).isEqualTo(2)
            assertThat(execution.rowCount).isNull()
        }

    @Test
    fun `statements run through the ambient executor join the caller's transaction`() =
        runBlocking<Unit> {
            // given
            val transactional = VertxTransactionalExecutor(Database.pool, PostgresDialect)
            val ambient = transactional.ambientQueryExecutor()

            // when
            val thrown =
                catchThrowable {
                    runBlocking {
                        transactional.transaction {
                            ambient.batch(
                                INSERT_GAME,
                                listOf(
                                    listOf(UUID.randomUUID(), "a"),
                                    listOf(UUID.randomUUID(), "b"),
                                ),
                            )
                            ambient.execute("UPDATE game SET name = name || '!'")
                            error("roll back")
                        }
                    }
                }

            // then
            assertThat(thrown).hasMessage("roll back")
            assertThat(Database.count("SELECT count(*) FROM game")).isEqualTo(0L)
        }

    @Test
    fun `ambient context of the caller reaches the block of a transaction`() =
        runBlocking<Unit> {
            // given
            val transactional = VertxTransactionalExecutor(Database.pool, PostgresDialect)

            // when
            val seen =
                withContext(TestPrincipalElement("user-1")) {
                    transactional.withTransaction {
                        currentPropagatedContext()[TestPrincipalElement]
                            ?.value
                    }
                }

            val none =
                transactional.withTransaction {
                    currentPropagatedContext()[TestPrincipalElement]
                        ?.value
                }

            // then
            assertThat(seen).isEqualTo("user-1")
            assertThat(none).isNull()
        }

    @Test
    fun `a thread-local context element is readable from plain code inside a transaction`() =
        runBlocking<Unit> {
            // given
            val transactional = VertxTransactionalExecutor(Database.pool, PostgresDialect)

            // when
            val seen =
                withContext(TestSyncPrincipalElement("user-1")) {
                    transactional.withTransaction { readSyncPrincipal() }
                }

            // then
            assertThat(seen).isEqualTo("user-1")
        }
}
