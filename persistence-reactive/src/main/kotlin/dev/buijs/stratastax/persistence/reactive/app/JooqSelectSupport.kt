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
package dev.buijs.stratastax.persistence.reactive.app

import dev.buijs.stratastax.persistence.reactive.api.JooqSearchParts
import dev.buijs.stratastax.persistence.reactive.api.applyJoins
import org.jooq.DSLContext
import org.jooq.Table

/**
 * Turns a [JooqSearchParts] (the same aliased/joined/MULTISET-aware projection
 * [dev.buijs.stratastax.persistence.reactive.api.JooqSearchQueryTranslator.translateById] builds
 * for [JooqReadAccess.findById]) into a runnable single-row select. Shared by
 * [JooqReadAccess.findById] and the post-write re-select
 * [JooqCreateAccess.create]/[JooqUpdateAccess.update] both do, so a write's result is read back
 * through the exact same shape a plain `findById` would see - never the raw, unaliased `RETURNING
 * *` row.
 */
internal fun DSLContext.selectByIdParts(
    table: Table<*>,
    parts: JooqSearchParts,
) = select(parts.selectFields)
    .from(table)
    .applyJoins(parts.joins)
    .where(parts.condition)
    .limit(1)

/**
 * [selectByIdParts]'s multi-row counterpart, for a batch `createAll`/ `updateAll`'s own bulk
 * re-select (built from
 * [dev.buijs.stratastax.persistence.reactive.api.JooqSearchQueryTranslator.translateByIds]) - no
 * `LIMIT`, since [parts] is expected to match every one of a batch's own root ids at once, not a
 * single row.
 */
internal fun DSLContext.selectByIdsParts(
    table: Table<*>,
    parts: JooqSearchParts,
) = select(parts.selectFields).from(table).applyJoins(parts.joins).where(parts.condition)
