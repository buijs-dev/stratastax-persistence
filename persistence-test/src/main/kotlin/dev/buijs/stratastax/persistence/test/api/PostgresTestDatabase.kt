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

import org.junit.jupiter.api.Assumptions
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * A real Postgres in a Testcontainers container, to run the code under test - and the planner -
 * against the actual schema.
 *
 * [start] first checks that Docker is available and otherwise aborts through a JUnit assumption, so
 * a test (typically its `@BeforeAll`) is reported as skipped rather than failed without Docker.
 */
public class PostgresTestDatabase(
    private val image: String = "postgres:16-alpine",
) {

    private var containerRef: PostgreSQLContainer<*>? = null

    /** The running container. */
    public val container: PostgreSQLContainer<*>
        get() = containerRef ?: error("PostgresTestDatabase.start() was not called")

    /** Starts the container, or skips the calling test when Docker is not available. */
    public fun start() {
        val dockerAvailable =
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        Assumptions.assumeTrue(dockerAvailable) { "Docker is not available - skipping test" }

        if (containerRef == null) {
            containerRef =
                PostgreSQLContainer(DockerImageName.parse(image)).also { it.start() }
        }
    }

    /** Stops the container. */
    public fun stop() {
        containerRef?.stop()
        containerRef = null
    }

    public val jdbcUrl: String
        get() = container.jdbcUrl

    /** The `postgresql://host:port/database` URL a Vert.x reactive client connects with. */
    public val reactiveUrl: String
        get() = "postgresql://$host:$port/$databaseName"

    public val host: String
        get() = container.host

    public val port: Int
        get() = container.firstMappedPort

    public val databaseName: String
        get() = container.databaseName

    public val username: String
        get() = container.username

    public val password: String
        get() = container.password

    /** A [PostgresQueryPlanAnalyzer] for this database. */
    public fun planAnalyzer(): PostgresQueryPlanAnalyzer =
        PostgresQueryPlanAnalyzer(jdbcUrl, username, password)

    /** Runs each of [statements] over JDBC, e.g. `CREATE TABLE ...`. */
    public fun executeDdl(vararg statements: String) {
        DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
            connection.createStatement().use { statement -> statements.forEach(statement::execute) }
        }
    }

    /**
     * Runs [sql] on its own JDBC connection, outside anything the code under test does, and returns
     * every row as column name to value - to verify what was actually committed.
     */
    public fun query(sql: String): List<Map<String, Any?>> =
        DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { resultSet ->
                    val columns =
                        (1..resultSet.metaData.columnCount).map {
                            resultSet.metaData
                                .getColumnLabel(
                                    it,
                                )
                        }

                    buildList {
                        while (resultSet.next()) {
                            add(
                                columns.associateWith { resultSet.getObject(it) },
                            )
                        }
                    }
                }
            }
        }

    /** [query] for a single numeric value, e.g. `select count(*) from ...`. */
    public fun queryForLong(sql: String): Long =
        (query(sql).single().values.single() as Number).toLong()

    /**
     * Makes Postgres log every statement it receives, prefixed with the backend process id and the
     * transaction id (`pid=123 txid=456 ...`) - the server's view of which statements ran on which
     * connection and in which transaction. Read it with [logs].
     */
    public fun enableStatementLogging() {
        executeDdl(
            "ALTER SYSTEM SET log_statement = 'all'",
            "ALTER SYSTEM SET log_line_prefix = 'pid=%p txid=%x '",
            "SELECT pg_reload_conf()",
        )
    }

    /** Everything the server logged so far, see [enableStatementLogging]. */
    public fun logs(): String = container.logs
}
