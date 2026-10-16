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

import dev.buijs.stratastax.persistence.core.spi.itemResultOf

/** Updates entities of type [T], identified by [ID], with commands of type [U]. */
public interface UpdateAccess<ID, U, T> {

    /**
     * Applies [command] to the entity with [id] and returns it as it was stored.
     *
     * [expectedVersion] is the optimistic-lock check: required (non-`null`) for an entity that has
     * a version column, ignored for one that has none.
     *
     * @throws EntityNotFoundException when there is no entity with [id].
     * @throws OptimisticLockException when [expectedVersion] no longer matches.
     * @throws ReferencedEntityNotFoundException when [command] references an entity that does not
     *   exist.
     * @throws IllegalArgumentException when the entity is versioned and [expectedVersion] is `null`.
     */
    public suspend fun update(
        id: ID,
        command: U,
        expectedVersion: Any? = null,
    ): T

    /**
     * Updates every one of [items]. One bad item never fails the others: the result holds one
     * [BatchItemResult] per item, in input order. An id that occurs more than once fails for every
     * occurrence after the first.
     *
     * Outside a [UnitOfWork.transaction] the batch is not atomic: see [CreateAccess.createAll].
     *
     * The default implementation calls [update] once per item.
     */
    public suspend fun updateAll(items: List<UpdateBatchItem<ID, U>>): BatchResult<T> =
        BatchResult(items.map { itemResultOf { update(it.id, it.command, it.expectedVersion) } })
}
