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

import dev.buijs.stratastax.search.core.api.Page
import dev.buijs.stratastax.search.core.api.SearchQuery

/** Reads entities of type [T], identified by [ID]. */
public interface ReadAccess<ID, T> {

    /**
     * Returns one page of the entities matching [search], in its sort order.
     *
     * @throws dev.buijs.stratastax.search.core.api.SearchException when [search] does not fit the
     *   entity (an unknown field, a cursor for another sort).
     */
    public suspend fun findAll(search: SearchQuery): Page<T>

    /** Returns the entity with [id], or `null` when there is none. */
    public suspend fun findById(id: ID): T?

    /** Counts every entity matching [search]'s filter; its sort, limit and cursor are ignored. */
    public suspend fun count(search: SearchQuery): Long
}
