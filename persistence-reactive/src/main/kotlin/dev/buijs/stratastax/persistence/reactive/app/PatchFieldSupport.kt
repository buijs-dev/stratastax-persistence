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

import dev.buijs.stratastax.entity.core.api.PatchField
import org.jooq.Field

/**
 * The jOOQ-aware counterpart to [PatchField.orKeep] for a generated `${Entity}UpdateMapper`:
 * [PatchField.Unset] means "leave this column out of the `SET` clause entirely" (`null`, filtered
 * out by the generated `listOfNotNull(...)` call), while [PatchField.Value] writes `field to
 * value` - checking the physical column's own nullability first ([Field.getDataType]'s
 * `nullable()`, sourced directly from the real database schema) so a caller explicitly clearing a
 * `NOT NULL` column fails with a clear message here rather than as an opaque SQL constraint
 * violation.
 */
fun <T> PatchField<T>.toUpdateEntry(field: Field<*>): Pair<Field<*>, Any?>? =
    when (this) {
        PatchField.Unset -> {
            null
        }
        is PatchField.Value -> {
            if (value == null && !field.dataType.nullable()) {
                throw IllegalArgumentException("Field '${field.name}' does not accept null")
            }

            field to value
        }
    }

/**
 * Converts a supplied value - e.g. an enum to its column string, a value class to its wrapped id -
 * while keeping the PATCH semantics: [PatchField.Unset] stays unset, and an explicit `null` stays
 * `null` without calling [transform]. Generated update mappers use this before [toUpdateEntry], the
 * same conversion the create side applies to a plain value.
 */
fun <T, R> PatchField<T>.map(transform: (T & Any) -> R?): PatchField<R> =
    when (this) {
        PatchField.Unset -> PatchField.Unset
        is PatchField.Value -> PatchField.Value(value?.let(transform))
    }
