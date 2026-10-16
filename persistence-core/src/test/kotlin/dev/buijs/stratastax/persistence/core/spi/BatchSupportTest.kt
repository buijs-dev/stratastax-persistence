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

import dev.buijs.stratastax.persistence.core.api.BatchItemResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

/** [runCatchingNonCancellation], [itemResultOf] and [toItemResult]. */
class BatchSupportTest {

    @Test
    fun `a value becomes a successful result`() {
        // given
        val block = { "ok" }

        // when
        val result = runCatchingNonCancellation(block)

        // then
        assertThat(result.getOrNull()).isEqualTo("ok")
    }

    @Test
    fun `an exception becomes a failed result`() {
        // given
        val failure = IllegalStateException("boom")

        // when
        val result = runCatchingNonCancellation { throw failure }

        // then
        assertThat(result.exceptionOrNull()).isSameAs(failure)
    }

    @Test
    fun `a cancellation is rethrown instead of captured`() {
        // given
        val cancellation = CancellationException("cancelled")

        // when
        val thrown = catchThrowable { runCatchingNonCancellation { throw cancellation } }

        // then
        assertThat(thrown).isSameAs(
            cancellation,
        )
    }

    @Test
    fun `an error is not captured`() {
        // given
        val error = StackOverflowError("deep")

        // when
        val thrown = catchThrowable { runCatchingNonCancellation { throw error } }

        // then
        assertThat(thrown).isSameAs(error)
    }

    @Test
    fun `itemResultOf maps a value and an exception to the matching item result`() {
        // given
        val failure = IllegalArgumentException("bad")

        // when
        val success = itemResultOf { 1 }

        val failed = itemResultOf { throw failure }

        // then
        assertThat(success).isEqualTo(BatchItemResult.Success(1))
        assertThat(failed).isEqualTo(BatchItemResult.Failure(failure))
    }

    @Test
    fun `toItemResult converts a Result`() {
        // given
        val failure = IllegalArgumentException("bad")

        // when
        val success = Result.success("a").toItemResult()
        val failed = Result.failure<String>(failure).toItemResult()

        // then
        assertThat(success).isEqualTo(BatchItemResult.Success("a"))
        assertThat(failed).isEqualTo(BatchItemResult.Failure(failure))
    }
}
