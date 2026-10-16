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

import dev.buijs.stratastax.entity.core.api.PatchField
import org.jooq.Field

/**
 * The `SET` entry for this patch value on [field]: `null` for [PatchField.Unset] (the column is
 * left out of the update), `field to value` for [PatchField.Value].
 *
 * @throws IllegalArgumentException when the value is `null` and [field] is not nullable.
 */
public fun <T> PatchField<T>.toUpdateEntry(field: Field<*>): Pair<Field<*>, Any?>? =
    when (this) {
        PatchField.Unset -> {
            null
        }
        is PatchField.Value -> {
            require(value != null || field.dataType.nullable()) {
                "Field '${field.name}' does not accept null"
            }

            field to value
        }
    }

/** Transforms a provided, non-null value with [transform]; `Unset` and `null` pass through. */
public fun <T, R> PatchField<T>.map(transform: (T & Any) -> R?): PatchField<R> =
    when (this) {
        PatchField.Unset -> PatchField.Unset
        is PatchField.Value -> PatchField.Value(value?.let(transform))
    }
