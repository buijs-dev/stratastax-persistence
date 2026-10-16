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

/**
 * A jOOQ-typed column/value bag, e.g. `CreateValues(PLAYSET.NAME to command.name, PLAYSET.OWNER_ID
 * to command.ownerId)` - what a generated `CreateMapper` produces and [JooqCreateAccess] turns into
 * an `INSERT`. Deliberately jOOQ-typed (unlike
 * [dev.buijs.stratastax.persistence.core.spi.CreateMapper] itself) - this is the physical layer,
 * where the columns being written are exactly as important as the values.
 *
 * Keyed by the looser [Field] rather than [org.jooq.TableField] deliberately - a generated
 * `CreateMapper` reads a field's physical column off the runtime-built `JooqFieldMapping`
 * (`mapping.fields.getValue("name").field`, typed [Field]) rather than referencing the jOOQ table
 * object literally, the same way generated `RowMapper`s already avoid embedding physical column
 * names as source text.
 */
class CreateValues(
    vararg entries: Pair<Field<*>, Any?>,
) {

    val values: Map<Field<*>, Any?> = entries.toMap()
}
