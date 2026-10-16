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
 * Same shape as [CreateValues], for `UPDATE`. The [List]-taking constructor is what a generated
 * `${Entity}UpdateMapper` actually calls (built via `listOfNotNull(...)`, since a
 * `PatchField`-typed entry can be `null` - meaning "leave this column out of the `SET` clause" -
 * see [dev.buijs.stratastax.entity.core.api.PatchField]); the `vararg` constructor stays for
 * hand-written/test callers.
 */
class UpdateValues(
    entries: List<Pair<Field<*>, Any?>>,
) {

    constructor(vararg entries: Pair<Field<*>, Any?>) : this(entries.toList())

    val values: Map<Field<*>, Any?> = entries.toMap()
}
