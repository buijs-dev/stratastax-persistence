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
package dev.buijs.stratastax.persistence.reactive.spi

import dev.buijs.stratastax.persistence.core.api.BatchItemResult
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * The control flow of a batch write around a failing statement ([batchOrEach]) and the inputs it
 * rejects before any SQL runs ([rejectedInputs]), without database.
 */
class BatchFallbackTest {

    @Test
    fun `a batch that fails as a whole is written again one input at a time`() =
        runBlocking<Unit> {
            // given
            val written = mutableListOf<String>()

            // when
            val result =
                batchOrEach(
                    listOf("a", "b", "c"),
                    all = { error("database down") },
                    each = { input ->
                        if (input == "b") {
                            error("b is invalid")
                        } else {
                            input.also { written += it }
                        }
                    },
                )

            // then - one failure of its own, the others written
            assertThat(written).containsExactly("a", "c")
            assertThat(
                result.items.map { it is BatchItemResult.Success },
            ).containsExactly(true, false, true)
            assertThat(
                (result.items[1] as BatchItemResult.Failure).error,
            ).hasMessage("b is invalid")
        }

    @Test
    fun `a batch of one input reports its failure without trying again`() =
        runBlocking<Unit> {
            // given
            var attempts = 0

            // when
            val result =
                batchOrEach(
                    listOf("a"),
                    all = {
                        attempts++
                        error("database down")
                    },
                    each = {
                        attempts++
                        it
                    },
                )

            // then
            assertThat(
                (result.items.single() as BatchItemResult.Failure).error,
            ).hasMessage("database down")
            assertThat(attempts).isEqualTo(1)
        }

    @Test
    fun `a cancellation stops a batch instead of being recorded as a failed input`() {
        // given
        val all: suspend (
            List<String>,
        ) -> List<Result<String>> = { throw CancellationException("cancelled") }

        // when
        val thrown =
            catchThrowable { runBlocking { batchOrEach(listOf("a", "b"), all, each = { it }) } }

        // then
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `a rejected input fails as it is, also in the fallback`() =
        runBlocking<Unit> {
            // given
            val id = UUID.randomUUID()
            val ids = listOf(id, UUID.randomUUID(), id, UUID.randomUUID())
            val rejected =
                rejectedInputs("PLAYSET", ids, listOf(1L, null, 1L, "x"), SqlVersion("VERSION"))

            // when
            val result =
                batchOrEach(
                    ids,
                    all = { error("database down") },
                    each = { it },
                    rejected = rejected,
                )

            // then
            val errors = result.items.map { (it as? BatchItemResult.Failure)?.error?.message }

            assertThat(errors[0]).isNull()
            assertThat(errors[1]).contains("expected version is required")
            assertThat(errors[2]).contains("more than once")
            assertThat(errors[3]).contains("is not a valid 'VERSION' value")
        }

    @Test
    fun `an unversioned entity needs no expected version`() {
        // given
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID())

        // when
        val rejected = rejectedInputs("PLAYSET", ids, listOf(null, null), version = null)

        // then
        assertThat(rejected).containsExactly(null, null)
    }
}
