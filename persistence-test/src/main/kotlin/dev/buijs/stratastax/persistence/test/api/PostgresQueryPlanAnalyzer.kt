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

import dev.buijs.stratastax.persistence.core.api.RenderedQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.DriverManager
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) <sql>` for [RenderedQuery] over a plain JDBC
 * connection (deliberately not the [dev.buijs.stratastax.persistence.reactive.api.QueryExecutor]/
 * Vert.x reactive path production uses - this only ever needs to run in tests, and a throwaway JDBC
 * connection per call keeps this testkit's own dependency footprint to `persistence-api` + the
 * Postgres JDBC driver, no `persistence-jooq`/`persistence-vertx`).
 *
 * **Runs the query for real** (`ANALYZE`) - never point this at a production database, and never
 * call it from anything but test code.
 */
class PostgresQueryPlanAnalyzer(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String,
) : QueryPlanAnalyzer {

    override suspend fun analyze(query: RenderedQuery): QueryPlan =
        withContext(Dispatchers.IO) {
            DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
                // RenderedSql.sql carries Postgres-native $1, $2, ...
                // placeholders
                // (rendered for the Vert.x reactive client) - plain JDBC's own
                // PreparedStatement parameter parser only recognizes `?`, so
                // convert back; the positional order is unaffected since $1,
                // $2,
                // ... always appear in the same left-to-right order the ?
                // placeholders they replaced did.
                val jdbcSql = query.sql.replace(Regex("\\$\\d+"), "?")
                connection
                    .prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $jdbcSql")
                    .use { statement ->
                        query.parameters.forEachIndexed { index, value ->
                            statement.setObject(index + 1, value)
                        }

                        statement.executeQuery().use { resultSet ->
                            check(resultSet.next()) { "EXPLAIN returned no rows" }

                            parse(resultSet.getString(1))
                        }
                    }
            }
        }

    private fun parse(json: String): QueryPlan {
        @Suppress("UNCHECKED_CAST")
        val root = (MiniJson.parse(json) as List<Map<String, Any?>>).single()

        @Suppress("UNCHECKED_CAST")
        val plan = root["Plan"] as Map<String, Any?>

        return QueryPlan(
            planningTime = (root["Planning Time"] as? Double ?: 0.0).milliseconds,
            executionTime = (root["Execution Time"] as? Double ?: 0.0).milliseconds,
            root = toPlanNode(plan),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun toPlanNode(plan: Map<String, Any?>): PlanNode =
        PlanNode(
            nodeType = plan["Node Type"] as String,
            relationName = plan["Relation Name"] as? String,
            indexName = plan["Index Name"] as? String,
            actualRows = (plan["Actual Rows"] as? Double)?.toLong(),
            planRows = (plan["Plan Rows"] as? Double)?.toLong(),
            actualLoops = (plan["Actual Loops"] as? Double)?.toLong(),
            sharedHitBlocks = (plan["Shared Hit Blocks"] as? Double)?.toLong(),
            sharedReadBlocks = (plan["Shared Read Blocks"] as? Double)?.toLong(),
            rowsRemovedByFilter = (plan["Rows Removed by Filter"] as? Double)?.toLong(),
            children =
                (plan["Plans"] as? List<Map<String, Any?>>)?.map(
                    ::toPlanNode,
                ) ?: emptyList(),
        )
}
