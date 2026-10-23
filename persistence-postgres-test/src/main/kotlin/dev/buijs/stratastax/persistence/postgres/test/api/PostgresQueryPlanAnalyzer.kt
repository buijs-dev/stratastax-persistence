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
@file:Suppress("SqlSourceToSinkFlow")

package dev.buijs.stratastax.persistence.postgres.test.api

import dev.buijs.stratastax.persistence.core.spi.RenderedQuery
import dev.buijs.stratastax.persistence.test.api.PlanNode
import dev.buijs.stratastax.persistence.test.api.QueryPlan
import dev.buijs.stratastax.persistence.test.api.QueryPlanAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.util.PGobject
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URL
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` for a statement over its own JDBC connection.
 *
 * `ANALYZE` really executes the statement, so it runs inside a transaction that is always rolled
 * back: analyzing an `INSERT`, `UPDATE` or `DELETE` leaves no trace. Still, never point this at a
 * database other than a test database.
 */
public class PostgresQueryPlanAnalyzer(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String,
) : QueryPlanAnalyzer {

    private val mapper = JsonMapper.builder().build()

    override suspend fun analyze(query: RenderedQuery): QueryPlan =
        withContext(Dispatchers.IO) {
            DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
                connection.autoCommit = false
                try {
                    explain(connection, query)
                } finally {
                    connection.rollback()
                }
            }
        }

    private fun explain(
        connection: Connection,
        query: RenderedQuery,
    ): QueryPlan =
        connection
            .prepareStatement(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ${toJdbcPlaceholders(query.sql)}",
            ).use { statement ->
                query.parameters.forEachIndexed {
                    index,
                    value,
                    ->
                    statement.setObject(index + 1, toJdbc(connection, mapper, value))
                }

                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "EXPLAIN returned no rows" }

                    parse(resultSet.getString(1))
                }
            }

    private fun parse(json: String): QueryPlan {
        val root = mapper.readTree(json).single()
        return QueryPlan(
            planningTime = root.path("Planning Time").asDouble(0.0).milliseconds,
            executionTime = root.path("Execution Time").asDouble(0.0).milliseconds,
            root = toPlanNode(root.path("Plan")),
        )
    }

    private fun toPlanNode(plan: JsonNode): PlanNode =
        PlanNode(
            nodeType = plan.path("Node Type").asString(),
            relationName = plan.textOrNull("Relation Name"),
            indexName = plan.textOrNull("Index Name"),
            actualRows = plan.longOrNull("Actual Rows"),
            planRows = plan.longOrNull("Plan Rows"),
            actualLoops = plan.longOrNull("Actual Loops"),
            sharedHitBlocks = plan.longOrNull("Shared Hit Blocks"),
            sharedReadBlocks = plan.longOrNull("Shared Read Blocks"),
            rowsRemovedByFilter = plan.longOrNull("Rows Removed by Filter"),
            children = (plan.path("Plans") as Iterable<JsonNode>).map(::toPlanNode),
        )

    private fun JsonNode.textOrNull(name: String): String? =
        get(name)
            ?.takeUnless { it.isNull }
            ?.asString()

    private fun JsonNode.longOrNull(name: String): Long? =
        get(name)
            ?.takeIf { it.isNumber }
            ?.asDouble()
            ?.toLong()
}

/**
 * The SQL with the driver's native `$1, $2, ...` placeholders replaced by JDBC's `?` - outside
 * string literals and quoted identifiers, which are left as they are.
 */
internal fun toJdbcPlaceholders(sql: String): String =
    buildString {
        var index = 0
        var quote: Char? = null
        while (index < sql.length) {
            val char = sql[index]
            when {
                quote != null -> {
                    append(char)
                    if (char == quote) quote = null
                    index++
                }
                char == '\'' || char == '"' -> {
                    quote = char
                    append(char)
                    index++
                }
                char == '$' && index + 1 < sql.length && sql[index + 1].isDigit() -> {
                    append('?')
                    index++
                    while (index < sql.length && sql[index].isDigit()) index++
                }
                else -> {
                    append(char)
                    index++
                }
            }
        }
    }

/**
 * A bind value as JDBC takes it, the way the reactive executor binds it: a `Map` (or a Vert.x JSON
 * value, matched by name so this module needs no Vert.x) as a jsonb `PGobject`, an array as a SQL
 * array, a `URI`/`URL` as text, an enum by name and an `Instant` as a UTC `OffsetDateTime`.
 */
private fun toJdbc(
    connection: Connection,
    mapper: JsonMapper,
    value: Any?,
): Any? =
    when {
        value == null -> {
            null
        }
        value is Map<*, *> -> {
            json(mapper.writeValueAsString(value))
        }
        value.javaClass.name in VERTX_JSON -> {
            json(value.toString())
        }
        value is Array<*> -> {
            connection.createArrayOf(sqlArrayType(value), value)
        }
        value is URI || value is URL -> {
            value.toString()
        }
        value is Enum<*> -> {
            value.name
        }
        value is Instant -> {
            value.atOffset(ZoneOffset.UTC)
        }
        else -> {
            value
        }
    }

private val VERTX_JSON = setOf("io.vertx.core.json.JsonObject", "io.vertx.core.json.JsonArray")

private fun json(text: String): PGobject =
    PGobject().apply {
        type = "jsonb"
        value = text
    }

private fun sqlArrayType(array: Array<*>): String =
    when (array.javaClass.componentType) {
        UUID::class.java -> "uuid"
        Long::class.java -> "int8"
        Int::class.java -> "int4"
        else -> "text"
    }
