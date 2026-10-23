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
import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching], but for implementing batch calls: catches an [Exception] as a failed
 * [Result], and lets everything else through.
 *
 * - A [CancellationException] is rethrown: a canceled coroutine must stop and unwind (rolling back
 *   its transaction), not be recorded as one failed batch item while the rest carries on.
 * - An [Error] (e.g. [OutOfMemoryError]) is not caught: it is not a property of one input.
 */
public inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

/** One batch input's outcome, computed with [runCatchingNonCancellation]. */
public inline fun <T> itemResultOf(block: () -> T): BatchItemResult<T> =
    runCatchingNonCancellation(block).toItemResult()

/** Converts a [Result] into the matching [BatchItemResult]. */
public fun <T> Result<T>.toItemResult(): BatchItemResult<T> =
    fold(onSuccess = { BatchItemResult.Success(it) }, onFailure = { BatchItemResult.Failure(it) })
