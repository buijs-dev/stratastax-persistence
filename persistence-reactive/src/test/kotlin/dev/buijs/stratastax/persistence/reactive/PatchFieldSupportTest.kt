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
package dev.buijs.stratastax.persistence.reactive

import dev.buijs.stratastax.entity.core.api.PatchField
import dev.buijs.stratastax.persistence.reactive.app.toUpdateEntry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Test

internal class PatchFieldSupportTest {

    private val nullableField = DSL.field(DSL.name("name"), SQLDataType.VARCHAR)
    private val nonNullableField = DSL.field(DSL.name("name"), SQLDataType.VARCHAR.nullable(false))

    @Test
    fun `Unset produces no SET entry`() {
        assertThat(PatchField.Unset.toUpdateEntry(nullableField)).isNull()
    }

    @Test
    fun `Value writes the field to the provided value`() {
        val result = PatchField.Value("Catan").toUpdateEntry(nullableField)

        assertThat(result).isEqualTo(nullableField to "Catan")
    }

    @Test
    fun `Value(null) on a nullable column writes NULL`() {
        val result = PatchField.Value<String>(null).toUpdateEntry(nullableField)

        assertThat(result).isEqualTo(nullableField to null)
    }

    @Test
    fun `Value(null) on a non-nullable column throws`() {
        assertThatThrownBy { PatchField.Value<String>(null).toUpdateEntry(nonNullableField) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
