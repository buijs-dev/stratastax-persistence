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
import dev.buijs.stratastax.persistence.reactive.app.map
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class PatchFieldMapTest {

    private enum class Status {

        Pending,
    }

    @Test
    fun `a supplied value is converted`() {
        val field: PatchField<Status> = PatchField.Value(Status.Pending)

        assertThat(field.map { it.name.uppercase() }).isEqualTo(PatchField.Value("PENDING"))
    }

    @Test
    fun `an explicit null stays null without converting`() {
        val field: PatchField<Status> = PatchField.Value(null)

        assertThat(field.map<Status, String> { error("must not be called") })
            .isEqualTo(PatchField.Value<String>(null))
    }

    @Test
    fun `unset stays unset`() {
        val field: PatchField<Status> = PatchField.Unset

        assertThat(field.map { it.name }).isEqualTo(PatchField.Unset)
    }
}
