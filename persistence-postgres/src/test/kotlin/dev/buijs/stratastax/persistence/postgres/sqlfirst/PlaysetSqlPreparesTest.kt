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
package dev.buijs.stratastax.persistence.postgres.sqlfirst

import dev.buijs.stratastax.entity.core.api.EntityField
import dev.buijs.stratastax.entity.core.api.EntityFieldType
import dev.buijs.stratastax.persistence.postgres.testfixtures.Database
import dev.buijs.stratastax.persistence.postgres.testfixtures.Searches.search
import dev.buijs.stratastax.persistence.reactive.spi.SqlType
import dev.buijs.stratastax.persistence.reactive.spi.countQuery
import dev.buijs.stratastax.persistence.reactive.spi.lockQuery
import dev.buijs.stratastax.persistence.reactive.spi.pageQuery
import dev.buijs.stratastax.search.core.api.ComparisonExpression
import dev.buijs.stratastax.search.core.api.ComparisonOperator
import dev.buijs.stratastax.search.core.api.FilterValue
import io.smallrye.mutiny.coroutines.awaitSuspending
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The literal SQL of the playset is valid for the schema: Postgres prepares every statement of
 * [PlaysetSql], and every search statement [PlaysetSearchSql] renders - which catches a typo or a
 * schema change before any test data is involved.
 */
internal class PlaysetSqlPreparesTest {

    companion object {

        /** Parts of statements, not statements themselves. */
        private val FRAGMENTS = setOf("COLUMNS", "FROM")

        @BeforeAll
        @JvmStatic
        fun start() = Database.start()
    }

    @Test
    fun `every statement of PlaysetSql prepares against the schema`() =
        runBlocking<Unit> {
            // given
            val statements =
                PlaysetSql::class.java.declaredFields
                    .filter {
                        Modifier.isStatic(it.modifiers) && it.type == String::class.java &&
                            it.name !in FRAGMENTS
                    }.associate { it.name to it.get(null) as String }

            // when
            val failures = statements.mapNotNull { (name, sql) -> failureOf(name, sql) }

            // then
            assertThat(statements).hasSizeGreaterThan(20)
            assertThat(failures).isEmpty()
        }

    @Test
    fun `a filter on every search field renders a statement that prepares against the schema`() =
        runBlocking<Unit> {
            // given
            val searches =
                PlaysetSearchSql.sql.fields.map { (name, field) ->
                    name to
                        search(
                            ComparisonExpression(
                                EntityField(name, EntityFieldType.STRING),
                                ComparisonOperator.EQUALS,
                                valueOf(field.type),
                            ),
                        )
                }

            // when
            val failures =
                searches.flatMap { (name, query) ->
                    listOfNotNull(
                        failureOf("$name page", PlaysetSearchSql.sql.pageQuery(query).sql),
                        failureOf("$name count", PlaysetSearchSql.sql.countQuery(query).sql),
                        failureOf("$name lock", PlaysetSearchSql.sql.lockQuery(query).sql),
                    )
                }

            // then
            assertThat(searches).hasSize(PlaysetSearchSql.sql.fields.size)
            assertThat(failures).isEmpty()
        }

    private fun valueOf(type: SqlType): FilterValue =
        when (type) {
            SqlType.UUID -> {
                FilterValue.UuidValue(UUID.randomUUID())
            }
            SqlType.TIMESTAMPTZ, SqlType.TIMESTAMP, SqlType.DATE -> {
                FilterValue.DateTimeValue(
                    Instant.EPOCH,
                )
            }
            SqlType.BIGINT, SqlType.INTEGER, SqlType.SMALLINT, SqlType.NUMERIC -> {
                FilterValue
                    .NumberValue(
                        BigDecimal.ONE,
                    )
            }
            SqlType.BOOLEAN -> {
                FilterValue.BooleanValue(true)
            }
            SqlType.JSONB -> {
                FilterValue.JsonValue("{}")
            }
            SqlType.TEXT, SqlType.VARCHAR -> {
                FilterValue.StringValue("x")
            }
            else -> {
                throw IllegalArgumentException("No sample value for $type")
            }
        }

    /** Why Postgres cannot prepare [sql], or `null` when it can. */
    private suspend fun failureOf(
        name: String,
        sql: String,
    ): String? {
        val connection = Database.pool.connection.awaitSuspending()
        return try {
            connection
                .prepare(sql)
                .awaitSuspending()
                .close()
                .awaitSuspending()
            null
        } catch (e: Exception) {
            "$name: ${e.message}"
        } finally {
            connection.close().awaitSuspending()
        }
    }
}
