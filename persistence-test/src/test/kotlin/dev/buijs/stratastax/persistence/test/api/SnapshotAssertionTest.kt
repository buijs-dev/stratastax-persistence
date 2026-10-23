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

import dev.buijs.stratastax.persistence.core.spi.QueryExecution
import dev.buijs.stratastax.persistence.core.spi.QueryOperation
import dev.buijs.stratastax.persistence.core.spi.RenderedQuery
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.ZERO

/** Recording, comparing, and re-recording snapshots of the statements of an execution. */
class SnapshotAssertionTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `records a missing snapshot on first run`() {
        // given
        val execution = executionOf("select 1", "select 2")

        // when
        execution.check()

        // then
        assertThat(files()).containsExactly("query-001.sql", "query-002.sql")
        assertThat(directory.resolve("scenario/query-002.sql").readText()).isEqualTo("select 2")
    }

    @Test
    fun `passes when nothing changed`() {
        // given
        executionOf("select 1").check()

        // when
        executionOf("select 1").check()

        // then
    }

    @Test
    fun `fails on changed, added and removed statements`() {
        // given
        executionOf("select 1", "select 2").check()

        // when
        val thrown1 =
            catchThrowable {
                executionOf("select 9", "select 2").check()
            }

        val thrown2 =
            catchThrowable {
                executionOf("select 1", "select 2", "select 3").check()
            }

        val thrown3 =
            catchThrowable {
                executionOf("select 1").check()
            }

        // then
        assertThat(thrown1).hasMessageContaining("query-001.sql changed")
        assertThat(thrown2).hasMessageContaining("query-003.sql is new")
        assertThat(thrown3).hasMessageContaining("query-002.sql no longer runs")
    }

    @Test
    fun `update mode re-records and removes stale files`() {
        // given
        executionOf("select 1", "select 2").check()

        // when
        executionOf("select 3").check(update = true)

        // then
        assertThat(files()).containsExactly("query-001.sql")
        assertThat(directory.resolve("scenario/query-001.sql").readText()).isEqualTo("select 3")
    }

    @Test
    fun `fails instead of recording a missing snapshot on CI`() {
        // given
        val execution = executionOf("select 1")

        // when
        val thrown =
            catchThrowable {
                execution.check(onCi = true)
            }

        // then
        assertThat(thrown).hasMessageContaining("No persistence snapshot 'scenario' recorded")
        assertThat(directory.resolve("scenario").exists()).isFalse()
    }

    private fun executionOf(vararg sql: String) =
        TestExecution(
            result = null,
            error = null,
            queries =
                sql.map {
                    ObservedQuery(
                        QueryExecution(
                            RenderedQuery(it, emptyList()),
                            QueryOperation.of(it),
                            ZERO,
                            Instant.EPOCH,
                            1,
                        ),
                    )
                },
        )

    private fun TestExecution<*>.check(
        update: Boolean = false,
        onCi: Boolean = false,
    ) = matchSnapshot("scenario", directory, update, onCi)

    private fun files() =
        directory
            .resolve("scenario")
            .listDirectoryEntries()
            .map { it.name }
            .sorted()

}
