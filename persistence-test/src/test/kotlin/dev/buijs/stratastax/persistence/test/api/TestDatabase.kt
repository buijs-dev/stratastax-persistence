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

import dev.buijs.stratastax.persistence.postgres.test.api.PostgresTestDatabase
import io.vertx.mutiny.pgclient.PgBuilder
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.PoolOptions

/**
 * One Postgres container and pool for every integration test of this module, with the table the
 * tests query: `playset (id, name, payload)`, indexed on `name`.
 */
internal object TestDatabase {

    val postgres = PostgresTestDatabase()

    val pool: Pool by lazy {
        PgBuilder
            .pool()
            .connectingTo(
                PgConnectOptions()
                    .setHost(postgres.host)
                    .setPort(postgres.port)
                    .setDatabase(postgres.databaseName)
                    .setUser(postgres.username)
                    .setPassword(postgres.password),
            ).with(PoolOptions().setMaxSize(4))
            .build()
    }

    @Volatile private var schemaCreated = false

    /** Starts the container with the schema or skips the calling test without Docker. */
    @Synchronized
    fun start() {
        postgres.start()
        if (!schemaCreated) {
            postgres.executeDdl(
                "CREATE TABLE playset (id BIGSERIAL PRIMARY KEY, name VARCHAR(255) NOT NULL, payload JSONB)",
                "CREATE INDEX playset_name_idx ON playset (name)",
            )
            schemaCreated = true
        }
    }

    /** Replaces the rows of `playset` with [count] generated rows and refreshes the statistics. */
    fun seed(count: Int) {
        postgres.executeDdl(
            "TRUNCATE playset RESTART IDENTITY",
            "INSERT INTO playset (name) SELECT 'playset ' || n FROM generate_series(1, $count) AS n",
            "ANALYZE playset",
        )
    }
}
