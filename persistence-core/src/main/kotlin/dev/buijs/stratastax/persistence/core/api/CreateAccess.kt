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

/** Creates entities of type [T] from commands of type [C]. */
public interface CreateAccess<C, T> {

    /**
     * Creates one entity from [command] and returns it as it was stored.
     *
     * @throws ReferencedEntityNotFoundException when [command] references an entity that does not
     *   exist.
     */
    public suspend fun create(command: C): T

    /**
     * Creates an entity for every one of [commands]. One bad command never fails the others: the
     * result holds one [BatchItemResult] per command, in input order.
     *
     * Outside a [UnitOfWork.transaction] the batch is not atomic: the commands that succeed are
     * stored even when others fail. Call [orThrow] inside a transaction for all-or-nothing.
     *
     * The default implementation calls [create] once per command.
     */
    public suspend fun createAll(commands: List<C>): BatchResult<T> =
        BatchResult(commands.map { itemResultOf { create(it) } })
}
