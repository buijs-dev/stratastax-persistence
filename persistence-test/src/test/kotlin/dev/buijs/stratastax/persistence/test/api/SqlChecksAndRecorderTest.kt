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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.ZERO

/** The SQL text checks, and the recorder under concurrent reports. */
class SqlChecksAndRecorderTest {

    @Test
    fun `matches tables, columns and predicates by word, ignoring case`() {
        val statements =
            listOf(
                $$"select g.name from game g join playset p on p.game_id = g.id where p.name = $1",
            )
        assertThat(SqlChecks.usesTable(statements, "PLAYSET")).isTrue
        assertThat(SqlChecks.usesTable(statements, "play")).isFalse
        assertThat(SqlChecks.usesColumn(statements, "game_id")).isTrue
        assertThat(SqlChecks.usesPredicate(statements, "name")).isTrue
        assertThat(SqlChecks.usesPredicate(statements, "game_id")).isFalse
        assertThat(SqlChecks.doesNotContain(statements, "ORDER BY")).isTrue
    }

    @Test
    fun `records every statement reported concurrently`() =
        runBlocking {
            // given
            val recorder = QueryExecutionRecorder()
            val execution =
                QueryExecution(
                    query = RenderedQuery("select 1", emptyList()),
                    operation = QueryOperation.SELECT,
                    duration = ZERO,
                    rowCount = 1,
                )

            // when
            (1..1_000)
                .map {
                    async(
                        Dispatchers.Default,
                    ) { recorder.executed(execution) }
                }.awaitAll()

            // then
            assertThat(recorder.executions()).hasSize(1_000)

            // and
            recorder.clear()
            assertThat(recorder.executions()).isEmpty()
        }
}
