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

/** One input of [UpdateAccess.updateAll]: the arguments of one [UpdateAccess.update] call. */
public data class UpdateBatchItem<ID, U>(
    val id: ID,
    val command: U,
    val expectedVersion: Any? = null,
)

/** One input of [DeleteAccess.deleteAll]: the arguments of one [DeleteAccess.delete] call. */
public data class DeleteBatchItem<ID>(
    val id: ID,
    val expectedVersion: Any? = null,
)

/**
 * The outcome of one input of a batch call: [Success] with what the single-item call would have
 * returned, or [Failure] with what it would have thrown (e.g. [EntityNotFoundException],
 * [OptimisticLockException], [ReferencedEntityNotFoundException]).
 */
public sealed class BatchItemResult<out T> {

    /** The input succeeded with [value]. */
    public data class Success<T>(
        val value: T,
    ) : BatchItemResult<T>()

    /** The input failed with [error]. */
    public data class Failure(
        val error: Throwable,
    ) : BatchItemResult<Nothing>()
}

/**
 * The result of a batch create/update/delete call: exactly one entry in [items] per input, in the
 * same order as the inputs, so it can be `zip`ped back onto them.
 */
public data class BatchResult<T>(
    val items: List<BatchItemResult<T>>,
) {

    /** The values of the inputs that succeeded, in input order. */
    val successes: List<T>
        get() = items.filterIsInstance<BatchItemResult.Success<T>>().map { it.value }

    /** The inputs that failed, in input order. */
    val failures: List<BatchItemResult.Failure>
        get() = items.filterIsInstance<BatchItemResult.Failure>()
}

/**
 * Returns [BatchResult.successes], or throws [BatchFailedException] when any input failed.
 *
 * Inside [UnitOfWork.transaction] this turns a best-effort batch into an all-or-nothing one: the
 * exception rolls back the inputs that did succeed.
 */
public fun <T> BatchResult<T>.orThrow(): List<T> {
    val failures = failures
    if (failures.isNotEmpty()) throw BatchFailedException(failures.map { it.error })
    return successes
}
