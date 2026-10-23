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
package dev.buijs.stratastax.persistence.postgres.test.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** [PostgresTestDatabase]: connection details, direct queries and server-side statement logging. */
internal class PostgresTestDatabaseIntegrationTest {

    companion object {

        @BeforeAll
        @JvmStatic
        fun start() = TestDatabase.start()
    }

    private val database = TestDatabase.postgres

    @Test
    fun `exposes the connection details of the container`() {
        // given
        val container = database.container

        // when
        val url = database.reactiveUrl

        // then
        assertThat(
            url,
        ).isEqualTo(
            "postgresql://${container.host}:${container.firstMappedPort}/${container.databaseName}",
        )
        assertThat(database.jdbcUrl).startsWith("jdbc:postgresql://")
        assertThat(database.username).isEqualTo(container.username)
    }

    @Test
    fun `queries the database directly`() {
        // given
        TestDatabase.seed(2)

        // when
        val rows = database.query("SELECT id, name FROM playset ORDER BY id")
        val count = database.queryForLong("SELECT count(*) FROM playset")

        // then
        assertThat(rows).containsExactly(
            mapOf("id" to 1L, "name" to "playset 1"),
            mapOf(
                "id" to 2L,
                "name" to "playset 2",
            ),
        )
        assertThat(count).isEqualTo(2)
    }

    @Test
    fun `logs every statement on the server once statement logging is enabled`() {
        // given
        database.enableStatementLogging()

        // when
        database.query("SELECT 'logged-marker' AS marker")

        // then
        assertThat(database.logs()).contains("logged-marker").contains("pid=")
    }
}
