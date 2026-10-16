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

import dev.buijs.stratastax.persistence.core.api.QueryExecution
import dev.buijs.stratastax.persistence.core.api.QueryOperation
import dev.buijs.stratastax.persistence.core.api.RenderedQuery
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds

/**
 * Exercises [assertMatchesSnapshot] without needing a real database - it only ever reads/writes
 * plain files based on a [TestExecution] it's handed directly, so a hand-built one is enough (the
 * SQL/plan content it snapshots is already covered elsewhere by real-Postgres tests).
 */
internal class SnapshotAssertionIntegrationTest {

    private val snapshotRoot = Files.createTempDirectory("persistence-testkit-snapshots")

    @AfterEach
    fun cleanUp() {
        snapshotRoot.toFile().deleteRecursively()
    }

    private fun executionOf(sql: String): TestExecution<Unit> =
        TestExecution(
            result = Unit,
            error = null,
            queries =
                listOf(
                    ObservedQuery(
                        QueryExecution(
                            query = RenderedQuery(sql, emptyList()),
                            operation = QueryOperation.SELECT,
                            duration = 1.milliseconds,
                            rowCount = 1,
                        ),
                    ),
                ),
        )

    @Test
    fun `the first run records a snapshot and passes`() {
        val result =
            executionOf("select * from playset where id = ?1")
                .assertMatchesSnapshot("first-run", snapshotRoot)

        assertThat(Files.exists(snapshotRoot.resolve("first-run/query-001.sql"))).isTrue()
        assertThat(Files.readString(snapshotRoot.resolve("first-run/query-001.sql")))
            .isEqualTo("select * from playset where id = ?1")
        assertThat(result.result).isEqualTo(Unit)
    }

    @Test
    fun `a second run with the same SQL passes without changing the snapshot`() {
        executionOf("select * from playset where id = ?1")
            .assertMatchesSnapshot("stable", snapshotRoot)

        executionOf("select * from playset where id = ?1")
            .assertMatchesSnapshot("stable", snapshotRoot)
    }

    @Test
    fun `a changed query fails with both the previous and current SQL`() {
        executionOf("select * from playset where id = ?1")
            .assertMatchesSnapshot("changed", snapshotRoot)

        val error =
            catchThrowableOfType(AssertionError::class.java) {
                executionOf("select id, name from playset where id = ?1")
                    .assertMatchesSnapshot("changed", snapshotRoot)
            }

        assertThat(error.message!!.contains("SQL changed")).isTrue()
        assertThat(error.message!!.contains("select * from playset where id = ?1")).isTrue()
        assertThat(error.message!!.contains("select id, name from playset where id = ?1")).isTrue()
    }
}
