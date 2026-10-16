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

interface UpdateAccess<ID, U, T> {

    suspend fun update(
        id: ID,
        command: U,
        expectedVersion: Any? = null,
    ): T

    /**
     * Naive baseline: one [update] call per [items] entry, in order, collecting each one's own
     * success/failure rather than letting a single bad input fail the whole batch.
     */
    suspend fun updateAll(items: List<UpdateBatchItem<ID, U>>): BatchResult<T> =
        BatchResult(items.map { itemResultOf { update(it.id, it.command, it.expectedVersion) } })
}

/** Same shape as [CreateMapper], for the update command [U]. */
fun interface UpdateMapper<U, V> {

    fun map(command: U): V
}
