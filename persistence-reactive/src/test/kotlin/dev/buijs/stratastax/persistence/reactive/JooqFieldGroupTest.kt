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

import dev.buijs.stratastax.persistence.reactive.api.JooqFieldGroup
import dev.buijs.stratastax.search.core.spi.FieldGroupRelation
import org.assertj.core.api.Assertions.assertThat
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.impl.TableImpl
import org.junit.jupiter.api.Test
import java.util.UUID

/** Group names are rendered unquoted as table aliases, so a reserved word must not be one. */
internal class JooqFieldGroupTest {

    private object MEMBERSHIP : TableImpl<Record>(DSL.name("membership")) {

        val USER_ID: TableField<Record, UUID> = createField(DSL.name("user_id"), SQLDataType.UUID)
    }

    // Named like the jOOQ-generated table it stands in for.
    @Suppress("ktlint:standard:class-naming")
    private object APP_USER : TableImpl<Record>(DSL.name("app_user")) {

        val ID: TableField<Record, UUID> = createField(DSL.name("id"), SQLDataType.UUID)
    }

    private fun group(name: String) =
        JooqFieldGroup(name, FieldGroupRelation.OneToOne, MEMBERSHIP.USER_ID, APP_USER.ID)

    @Test
    fun `a reserved word gets a trailing underscore as table alias`() {
        assertThat(group("user").tableAlias).isEqualTo("user_")
        assertThat(group("Order").tableAlias).isEqualTo("Order_")
    }

    @Test
    fun `any other name is its own table alias`() {
        assertThat(group("recorderUser").tableAlias).isEqualTo("recorderUser")
        assertThat(group("member").tableAlias).isEqualTo("member")
    }
}
