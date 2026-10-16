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
package dev.buijs.stratastax.persistence.core.spi

import dev.buijs.stratastax.persistence.core.api.BatchFailedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

class BatchResultTest {

    @Test
    fun `runCatchingNonCancellation captures an ordinary failure`() {
        val result = runCatchingNonCancellation { error("boom") }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `runCatchingNonCancellation rethrows a cancellation instead of capturing it`() {
        assertThatThrownBy {
            runCatchingNonCancellation { throw CancellationException("cancelled") }
        }.isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `itemResultOf maps success and failure to batch item results`() {
        val failure = IllegalArgumentException("bad input")

        assertThat(itemResultOf { 42 }).isEqualTo(BatchItemResult.Success(42))
        assertThat(itemResultOf { throw failure }).isEqualTo(BatchItemResult.Failure(failure))
    }

    @Test
    fun `orThrow returns the successes when every item succeeded`() {
        val result = BatchResult(listOf(BatchItemResult.Success("a"), BatchItemResult.Success("b")))

        assertThat(result.orThrow()).containsExactly("a", "b")
    }

    @Test
    fun `orThrow throws every failure in input order, the first one as the cause`() {
        val first = IllegalArgumentException("first")
        val second = IllegalStateException("second")
        val result =
            BatchResult(
                listOf(
                    BatchItemResult.Failure(first),
                    BatchItemResult.Success("ok"),
                    BatchItemResult.Failure(second),
                ),
            )

        assertThatThrownBy { result.orThrow() }
            .isInstanceOfSatisfying(BatchFailedException::class.java) {
                assertThat(it.failures).containsExactly(first, second)
                assertThat(it.cause).isSameAs(first)
            }
    }
}
