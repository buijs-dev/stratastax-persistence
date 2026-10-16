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
import kotlin.coroutines.cancellation.CancellationException

data class UpdateBatchItem<ID, U>(
    val id: ID,
    val command: U,
    val expectedVersion: Any? = null,
)

data class DeleteBatchItem<ID>(
    val id: ID,
    val expectedVersion: Any? = null,
)

/**
 * One [CreateAccess.createAll]/[UpdateAccess.updateAll]/[DeleteAccess.deleteAll] input's own
 * outcome: [Success] with the same [T] a single [CreateAccess.create]/ [UpdateAccess.update] call
 * would have returned or [Failure] with whatever exception a single-item call for that same input
 * would have thrown (e.g. [dev.buijs.stratastax.persistence.core.api.EntityNotFoundException],
 * [dev.buijs.stratastax.persistence.core.api.OptimisticLockException],
 * [dev.buijs.stratastax.persistence.core.api.ReferencedEntityNotFoundException]).
 */
sealed class BatchItemResult<out T> {

    data class Success<T>(
        val value: T,
    ) : BatchItemResult<T>()

    data class Failure(
        val error: Throwable,
    ) : BatchItemResult<Nothing>()
}

/**
 * The result of a batch create/update/delete call: [items] has exactly one entry per input, **in
 * the same order** as the batch's own input list, so a caller can `zip` it back against that input
 * without any id bookkeeping of its own. A batch call never throws for a single bad input the way
 * its single-item counterpart does. Every input either succeeds or contributes a
 * [BatchItemResult.Failure] here, the rest still going through.
 */
data class BatchResult<T>(
    val items: List<BatchItemResult<T>>,
) {

    val successes: List<T>
        get() = items.filterIsInstance<BatchItemResult.Success<T>>().map { it.value }

    val failures: List<BatchItemResult.Failure>
        get() = items.filterIsInstance<BatchItemResult.Failure>()
}

/** Returns [BatchResult.successes], or throws [BatchFailedException] if any input failed. */
fun <T> BatchResult<T>.orThrow(): List<T> {
    val failures = failures
    if (failures.isNotEmpty()) throw BatchFailedException(failures.map { it.error })
    return successes
}

fun <T> Result<T>.toItemResult(): BatchItemResult<T> =
    fold(onSuccess = { BatchItemResult.Success(it) }, onFailure = { BatchItemResult.Failure(it) })

/**
 * [runCatching] that never catches a [CancellationException]: a cancelled coroutine must stop and
 * unwind (rolling back its transaction), not be recorded as one failed batch item while the rest of
 * the batch carries on.
 */
inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

/** One batch input's own outcome - see [runCatchingNonCancellation]. */
inline fun <T> itemResultOf(block: () -> T): BatchItemResult<T> =
    runCatchingNonCancellation(block).toItemResult()
