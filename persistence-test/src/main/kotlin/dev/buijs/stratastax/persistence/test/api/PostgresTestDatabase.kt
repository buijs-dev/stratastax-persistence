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
 * A real Postgres, via Testcontainers - the same "test the actual optimizer against the actual
 * schema" instance a [PersistenceTestHarness] scenario runs against, generalized out of the
 * container/DDL boilerplate `persistence-vertx`'s own `VertxQueryExecutorIntegrationTest` already
 * established.
 *
 * [start] calls [Assumptions.assumeTrue] before touching Testcontainers at all, so a caller
 * (typically a `@BeforeAll`) is reported as *skipped*, not *failed*, in an environment without
 * Docker.
 */
class PostgresTestDatabase(
    private val image: String = "postgres:16-alpine",
) {

    private var containerRef: PostgreSQLContainer<*>? = null

    val container: PostgreSQLContainer<*>
        get() = containerRef ?: error("PostgresTestDatabase.start() was not called")

    fun start() {
        val dockerAvailable =
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        Assumptions.assumeTrue(dockerAvailable) { "Docker is not available - skipping test" }

        containerRef = PostgreSQLContainer(DockerImageName.parse(image)).also { it.start() }
    }

    fun stop() {
        containerRef?.stop()
    }

    val jdbcUrl: String
        get() = container.jdbcUrl

    /** The `postgresql://host:port/database` URL a Vert.x reactive client connects with. */
    val reactiveUrl: String
        get() =
            "postgresql://${container.host}:${container.firstMappedPort}/${container.databaseName}"

    val host: String
        get() = container.host

    val port: Int
        get() = container.firstMappedPort

    val databaseName: String
        get() = container.databaseName

    val username: String
        get() = container.username

    val password: String
        get() = container.password

    /** Runs each of [statements] as a plain JDBC DDL statement, e.g. `CREATE TABLE ...`. */
    fun executeDdl(vararg statements: String) {
        DriverManager
            .getConnection(container.jdbcUrl, container.username, container.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statements.forEach(statement::execute)
                }
            }
    }

    /**
     * Runs [sql] directly on the database (its own JDBC connection, outside anything the code under
     * test does) and returns every row as column name to value - for verifying what was actually
     * committed, e.g. that no orphan rows were left behind.
     */
    fun query(sql: String): List<Map<String, Any?>> =
        DriverManager
            .getConnection(container.jdbcUrl, container.username, container.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { resultSet ->
                        val columns =
                            (1..resultSet.metaData.columnCount).map {
                                resultSet.metaData.getColumnLabel(it)
                            }

                        buildList {
                            while (resultSet.next()) {
                                add(columns.associateWith { resultSet.getObject(it) })
                            }
                        }
                    }
                }
            }

    /** [query] for a single numeric value, e.g. `select count(*) from ...`. */
    fun queryForLong(sql: String): Long = (query(sql).single().values.single() as Number).toLong()

    /**
     * Makes Postgres itself log every statement it receives, prefixed with the backend process id
     * and transaction id (`pid=123 txid=456 ...`) - the server-side view of which statements ran on
     * which connection and in which transaction, independent of any client-side listener. Read the
     * output with [logs].
     */
    fun enableStatementLogging() {
        executeDdl(
            "ALTER SYSTEM SET log_statement = 'all'",
            "ALTER SYSTEM SET log_line_prefix = 'pid=%p txid=%x '",
            "SELECT pg_reload_conf()",
        )
    }

    /** Everything the Postgres server logged so far (see [enableStatementLogging]). */
    fun logs(): String = container.logs
}
