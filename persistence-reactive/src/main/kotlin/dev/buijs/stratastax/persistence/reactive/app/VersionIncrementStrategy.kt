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

import org.jooq.Field
import org.jooq.TableField

/**
 * Computes an entity's new `version` value on `UPDATE`, used by [JooqUpdateAccess.update] in place
 * of a hardcoded `+1` - a consumer plugs in a different strategy (e.g. a step other than 1, or a
 * value derived from other columns on the same row) by registering their own bean of this type (a
 * Quarkus/CDI `@ApplicationScoped VersionIncrementStrategy` overrides the library-shipped default -
 * see `quarkus-starter`'s `DefaultVersionIncrementStrategy`).
 */
fun interface VersionIncrementStrategy {

    /**
     * Returns the jOOQ expression to write into [column] on this update - evaluated by the database
     * as part of the `UPDATE` statement itself, so it always sees the row's true current value with
     * no separate read and no race against a concurrent update. [column]'s own
     * [TableField.getTable] exposes any other column on the same row, for a strategy that needs
     * more than the version column alone.
     */
    fun next(column: TableField<*, *>): Field<*>
}
