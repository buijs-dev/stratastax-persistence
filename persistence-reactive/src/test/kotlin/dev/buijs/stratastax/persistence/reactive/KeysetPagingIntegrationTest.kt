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

import dev.buijs.stratastax.entity.core.api.EntityField
import dev.buijs.stratastax.entity.core.api.EntityFieldType
import dev.buijs.stratastax.entity.core.api.Sort
import dev.buijs.stratastax.entity.core.api.SortDirection
import dev.buijs.stratastax.entity.core.api.SortField
import dev.buijs.stratastax.persistence.core.spi.RowMapper
import dev.buijs.stratastax.persistence.reactive.api.JooqField
import dev.buijs.stratastax.persistence.reactive.api.JooqFieldMapping
import dev.buijs.stratastax.persistence.reactive.api.JooqReadAccess
import dev.buijs.stratastax.persistence.reactive.api.JooqSearchQueryTranslator
import dev.buijs.stratastax.persistence.reactive.api.VertxQueryExecutor
import dev.buijs.stratastax.search.core.api.CursorDirection
import dev.buijs.stratastax.search.core.api.CursorState
import dev.buijs.stratastax.search.core.api.SearchQuery
import dev.buijs.stratastax.search.core.spi.CursorElement
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jooq.Record
import org.jooq.SQLDialect
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.impl.TableImpl
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID

// Named like the jOOQ-generated table it stands in for.
@Suppress("ktlint:standard:class-naming")
private object KP_ITEM : TableImpl<Record>(DSL.name("kp_item")) {

    val ID: TableField<Record, UUID> = createField(DSL.name("id"), SQLDataType.UUID.nullable(false))
    val NAME: TableField<Record, String> = createField(DSL.name("name"), SQLDataType.VARCHAR)
    val SCORE: TableField<Record, Int> = createField(DSL.name("score"), SQLDataType.INTEGER)
    val CREATED_AT: TableField<Record, OffsetDateTime> =
        createField(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
}

private data class KpItem(
    val id: UUID,
    val name: String?,
    val score: Int?,
    val createdAt: OffsetDateTime?,
)

/**
 * Cursor paging against a real Postgres: forwards through every page and back again, over sort
 * fields with NULLs (text, number, timestamp) in both directions. Every row must come exactly once,
 * in the order of one unpaged query. Skipped without Docker, like
 * [VertxQueryExecutorIntegrationTest].
 */
internal class KeysetPagingIntegrationTest {

    companion object {

        private lateinit var postgres: PostgreSQLContainer<*>
        private lateinit var pool: io.vertx.mutiny.sqlclient.Pool

        @BeforeAll
        @JvmStatic
        fun setUp() {
            val dockerAvailable =
                runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
            Assumptions.assumeTrue(dockerAvailable) {
                "Docker is not available - skipping KeysetPagingIntegrationTest"
            }

            postgres = PostgreSQLContainer("postgres:16-alpine")
            postgres.start()
            // Duplicate and NULL sort values, so the tiebreaker and the NULL handling both matter.
            DriverManager
                .getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
                .use { connection ->
                    connection.createStatement().use {
                        it.execute(
                            """
                            CREATE TABLE kp_item (
                                id UUID PRIMARY KEY,
                                name VARCHAR(255),
                                score INTEGER,
                                created_at TIMESTAMPTZ
                            );
                            INSERT INTO kp_item VALUES
                                ('00000000-0000-0000-0000-000000000001', 'b',  3,    '2026-01-01T10:00:00Z'),
                                ('00000000-0000-0000-0000-000000000002', NULL, 1,    NULL),
                                ('00000000-0000-0000-0000-000000000003', 'a',  NULL, '2026-01-03T10:00:00.123456Z'),
                                ('00000000-0000-0000-0000-000000000004', 'b',  3,    NULL),
                                ('00000000-0000-0000-0000-000000000005', NULL, NULL, '2026-01-01T10:00:00Z'),
                                ('00000000-0000-0000-0000-000000000006', 'c',  2,    '2026-01-02T10:00:00Z'),
                                ('00000000-0000-0000-0000-000000000007', NULL, 2,    NULL);
                            """,
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
                    .with(
                        io.vertx.sqlclient
                            .PoolOptions()
                            .setMaxSize(2),
                    ).build()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            if (::pool.isInitialized) pool.close().await().indefinitely()
            if (::postgres.isInitialized) postgres.stop()
        }
    }

    private val fields =
        mapOf(
            "id" to EntityField("id", EntityFieldType.UUID, sortable = true),
            "name" to EntityField("name", EntityFieldType.STRING, sortable = true),
            "score" to EntityField("score", EntityFieldType.NUMBER, sortable = true),
            "created" to EntityField("created", EntityFieldType.DATETIME, sortable = true),
        )

    private val readAccess =
        object : JooqReadAccess<UUID, KpItem> {

            override val dsl = DSL.using(SQLDialect.POSTGRES)
            override val table = KP_ITEM
            override val rowMapper =
                RowMapper { row ->
                    KpItem(
                        row.uuid("id"),
                        row.stringOrNull("name"),
                        row.integerOrNull("score"),
                        row.offsetDateTimeOrNull("created"),
                    )
                }

            override val fieldMapping =
                JooqFieldMapping(
                    fields =
                        mapOf(
                            "id" to JooqField(field = KP_ITEM.ID, selectable = true),
                            "name" to JooqField(field = KP_ITEM.NAME, selectable = true),
                            "score" to JooqField(field = KP_ITEM.SCORE, selectable = true),
                            "created" to JooqField(field = KP_ITEM.CREATED_AT, selectable = true),
                        ),
                )
            override val queryExecutor = VertxQueryExecutor(pool)
            override val queryTranslator = JooqSearchQueryTranslator()
            override val idField = fields.getValue("id")
        }

    /** The cursor elements of [item], as a generated key extractor gives them. */
    private fun keysOf(
        item: KpItem,
        sort: Sort,
    ): List<CursorElement> =
        sort.fields.map {
            when (it.field.name) {
                "id" -> {
                    CursorElement.StringValue(item.id.toString())
                }
                "name" -> {
                    item.name?.let(CursorElement::StringValue) ?: CursorElement.NullValue
                }
                "score" -> {
                    item.score?.let(CursorElement.NumberValue::of) ?: CursorElement.NullValue
                }
                else -> {
                    item.createdAt?.let { t -> CursorElement.TimestampValue(t.toInstant()) }
                        ?: CursorElement.NullValue
                }
            }
        }

    private fun query(
        sort: Sort,
        limit: Int,
        cursor: CursorState?,
    ) = SearchQuery(limit = limit, sort = sort, filter = null, cursor = cursor)

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings =
            ["name:ASC", "name:DESC", "score:ASC", "score:DESC", "created:ASC", "created:DESC"],
    )
    fun `pages forwards and back over every row exactly once`(sortSpec: String) {
        runBlocking {
            val (field, direction) = sortSpec.split(":")
            val sort =
                Sort(
                    listOf(
                        SortField(fields.getValue(field), SortDirection.valueOf(direction)),
                        SortField(fields.getValue("id"), SortDirection.ASC),
                    ),
                )
            val expected = readAccess.findAll(query(sort, 100, null)).items

            val forwards = mutableListOf<KpItem>()
            var cursor: CursorState? = null
            do {
                val page = readAccess.findAll(query(sort, 2, cursor))
                forwards += page.items
                cursor = CursorState(keysOf(page.items.last(), sort), "hash")
            } while (page.hasMore)

            val backwards = mutableListOf<KpItem>()
            cursor = CursorState(keysOf(forwards.last(), sort), "hash", CursorDirection.BEFORE)
            do {
                val page = readAccess.findAll(query(sort, 2, cursor))
                backwards.addAll(0, page.items)
                cursor =
                    CursorState(keysOf(page.items.first(), sort), "hash", CursorDirection.BEFORE)
            } while (page.hasMore)

            assertThat(expected).hasSize(7)
            assertThat(forwards).containsExactlyElementsOf(expected)
            assertThat(backwards).containsExactlyElementsOf(expected.dropLast(1))
            // NULL is the largest value: last ascending, first descending.
            val nullsLast = direction == "ASC"
            val firstOrLast = if (nullsLast) expected.last() else expected.first()
            assertThat(keysOf(firstOrLast, sort).first()).isEqualTo(CursorElement.NullValue)
        }
    }
}
