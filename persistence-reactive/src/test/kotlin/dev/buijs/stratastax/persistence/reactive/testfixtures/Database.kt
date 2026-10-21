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
package dev.buijs.stratastax.persistence.reactive.testfixtures

import dev.buijs.stratastax.persistence.test.api.PostgresTestDatabase
import io.vertx.mutiny.pgclient.PgBuilder
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.PoolOptions

/**
 * One Postgres container and connection pool shared by every integration test of this module,
 * with the schema of [PlaysetSchema] created once. Tests call [start] in `@BeforeAll` (skipped
 * without Docker) and [truncate] before each test.
 */
internal object Database {

    val postgres = PostgresTestDatabase()

    val pool: Pool by lazy {
        val options =
            PgConnectOptions()
                .setHost(postgres.host)
                .setPort(postgres.port)
                .setDatabase(postgres.databaseName)
                .setUser(postgres.username)
                .setPassword(postgres.password)
        PgBuilder
            .pool()
            .connectingTo(options)
            .with(PoolOptions().setMaxSize(POOL_SIZE))
            .build()
    }

    /** The size of [pool]. */
    const val POOL_SIZE: Int = 4

    @Volatile private var schemaCreated = false

    /** Starts the container and creates the schema, or skips the calling test without Docker. */
    @Synchronized
    fun start() {
        postgres.start()
        if (!schemaCreated) {
            postgres.executeDdl(*PlaysetSchema.DDL.toTypedArray())
            schemaCreated = true
        }
    }

    /** Empties every table and resets the sequences. */
    fun truncate() {
        postgres.executeDdl(
            "TRUNCATE ${PlaysetSchema.TABLES.joinToString()} RESTART IDENTITY CASCADE",
        )
    }

    /** Runs [sql] over JDBC, outside the code under test. */
    fun jdbc(vararg sql: String) {
        postgres.executeDdl(*sql)
    }

    /** The rows of [sql], read over JDBC outside the code under test. */
    fun query(sql: String): List<Map<String, Any?>> = postgres.query(sql)

    /** The single number [sql] returns. */
    fun count(sql: String): Long = postgres.queryForLong(sql)
}
