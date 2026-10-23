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
package dev.buijs.stratastax.persistence.core.api

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test

/**
 * [BatchResult], [orThrow] and the default (one call per input) batch implementations of the
 * access interfaces.
 */
class BatchResultTest {

    private val failure = IllegalArgumentException("bad input")

    @Test
    fun `successes and failures keep input order`() {
        // given
        val result =
            BatchResult(
                listOf(
                    BatchItemResult.Success("a"),
                    BatchItemResult.Failure(failure),
                    BatchItemResult.Success("c"),
                ),
            )

        // when
        val successes = result.successes
        val failures = result.failures

        // then
        assertThat(successes).containsExactly("a", "c")
        assertThat(failures).containsExactly(BatchItemResult.Failure(failure))
    }

    @Test
    fun `orThrow returns every value when nothing failed`() {
        // given
        val result = BatchResult(listOf(BatchItemResult.Success(1), BatchItemResult.Success(2)))

        // when
        val values = result.orThrow()

        // then
        assertThat(values).containsExactly(1, 2)
    }

    @Test
    fun `orThrow throws a BatchFailedException holding every failure`() {
        // given
        val other = IllegalStateException("other")
        val result =
            BatchResult(
                listOf(
                    BatchItemResult.Failure(failure),
                    BatchItemResult.Success(1),
                    BatchItemResult.Failure(other),
                ),
            )

        // when
        val thrown = catchThrowable { result.orThrow() }

        // then
        assertThat(thrown)
            .isInstanceOfSatisfying(BatchFailedException::class.java) {
                assertThat(it.failures).containsExactly(failure, other)
            }
    }

    @Test
    fun `default createAll calls create per command and records each outcome`() {
        // given
        val access =
            object : CreateAccess<Int, String> {
                override suspend fun create(command: Int): String =
                    if (command < 0) throw failure else "created $command"
            }

        // when
        val result = runBlocking { access.createAll(listOf(1, -1, 2)) }

        // then
        assertThat(result.items)
            .containsExactly(
                BatchItemResult.Success("created 1"),
                BatchItemResult.Failure(failure),
                BatchItemResult.Success("created 2"),
            )
    }

    @Test
    fun `default updateAll passes id, command and version per item`() {
        // given
        val calls = mutableListOf<Triple<Long, String, Any?>>()
        val access =
            object : UpdateAccess<Long, String, String> {
                override suspend fun update(
                    id: Long,
                    command: String,
                    expectedVersion: Any?,
                ): String {
                    calls += Triple(id, command, expectedVersion)
                    return command
                }
            }

        // when
        val result =
            runBlocking {
                access.updateAll(listOf(UpdateBatchItem(1L, "x", 7L), UpdateBatchItem(2L, "y")))
            }

        // then
        assertThat(calls).containsExactly(Triple(1L, "x", 7L), Triple(2L, "y", null))
        assertThat(result.successes).containsExactly("x", "y")
    }

    @Test
    fun `default deleteAll records a failure without stopping the batch`() {
        // given
        val deleted = mutableListOf<Long>()
        val access =
            object : DeleteAccess<Long> {
                override suspend fun delete(
                    id: Long,
                    expectedVersion: Any?,
                ) {
                    if (id == 2L) throw EntityNotFoundException("playset", id)
                    deleted += id
                }
            }

        // when
        val result =
            runBlocking {
                access.deleteAll(
                    listOf(DeleteBatchItem(1L), DeleteBatchItem(2L), DeleteBatchItem(3L)),
                )
            }

        // then
        assertThat(deleted).containsExactly(1L, 3L)
        assertThat(result.items[0]).isEqualTo(BatchItemResult.Success(Unit))
        assertThat((result.items[1] as BatchItemResult.Failure).error)
            .isInstanceOf(EntityNotFoundException::class.java)
        assertThat(result.items[2]).isEqualTo(BatchItemResult.Success(Unit))
    }
}
