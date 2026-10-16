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
package dev.buijs.stratastax.persistence.reactive.spi

import org.jooq.Field
import org.jooq.TableField
import org.jooq.impl.DSL

/**
 * The optimistic-lock version column of an entity: every update and (soft-)delete checks the
 * caller's expected version against [column] and writes [increment]'s next value in the same
 * statement, so two writes based on the same version can never both succeed.
 */
public class VersionColumn(
    public val column: TableField<*, *>,
    public val increment: VersionIncrementStrategy = VersionIncrementStrategy.PLUS_ONE,
)

/** The version column of the rows of a one-to-many group, and the version a new row starts at. */
public class ChildVersion(
    public val column: TableField<*, *>,
    public val initial: InitialVersionStrategy = InitialVersionStrategy.ONE,
)

/** The next version of a version column, as an SQL expression evaluated in the write itself. */
public fun interface VersionIncrementStrategy {

    public fun next(column: TableField<*, *>): Field<*>

    public companion object {

        /** `column + 1`. */
        public val PLUS_ONE: VersionIncrementStrategy =
            VersionIncrementStrategy { column -> DSL.field("{0} + 1", column.dataType, column) }
    }
}

/** The version a newly inserted row starts at in a version column, as an SQL expression. */
public fun interface InitialVersionStrategy {

    public fun initial(column: TableField<*, *>): Field<*>

    public companion object {

        /** Starts every row at version `1`. */
        public val ONE: InitialVersionStrategy = of(1L)

        /** Starts every row at the constant [version]. */
        public fun of(version: Long): InitialVersionStrategy =
            InitialVersionStrategy { DSL.inline(version) }
    }
}
