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

import dev.buijs.stratastax.entity.core.api.SortDirection
import dev.buijs.stratastax.entity.core.api.SortField
import dev.buijs.stratastax.persistence.reactive.api.JooqField
import dev.buijs.stratastax.persistence.reactive.api.JooqFieldGroup
import dev.buijs.stratastax.persistence.reactive.api.JooqFieldMapping
import dev.buijs.stratastax.persistence.reactive.api.JooqSearchParts
import dev.buijs.stratastax.persistence.reactive.api.JooqSearchQueryTranslator
import dev.buijs.stratastax.persistence.reactive.api.applyJoins
import dev.buijs.stratastax.persistence.reactive.testfixtures.GAME
import dev.buijs.stratastax.persistence.reactive.testfixtures.MATCH
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.deletedAtField
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.gameNameField
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.idField
import dev.buijs.stratastax.search.core.api.FilterValue
import dev.buijs.stratastax.search.core.spi.FieldGroupRelation
import dev.buijs.stratastax.search.core.spi.StandardSearchConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.jooq.SQLDialect
import org.jooq.conf.ParamType
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.util.UUID

internal class JooqSearchQueryTranslatorByIdTest {

    private val translator = JooqSearchQueryTranslator()

    private val configuration =
        StandardSearchConfiguration(
            defaultSort = listOf(SortField(idField, SortDirection.ASC)),
            fields = listOf(idField, deletedAtField, gameNameField),
            tiebreakerField = idField,
        )

    private val mapping =
        JooqFieldMapping(
            fields =
                mapOf(
                    "id" to JooqField(field = MATCH.PUBLIC_ID, selectable = true),
                    "deletedAt" to
                        JooqField(
                            field = MATCH.STATUS,
                            selectable = true,
                            defaultFilterValue = FilterValue.NullValue,
                        ),
                    "gameName" to JooqField(field = GAME.NAME, selectable = true),
                ),
            groups =
                listOf(
                    JooqFieldGroup(
                        name = "game",
                        relation = FieldGroupRelation.OneToOne,
                        from = MATCH.ID,
                        to = GAME.ID,
                        fieldNames = listOf("gameName"),
                    ),
                ),
        )

    private fun fieldByName(name: String) = configuration.fields.first { it.name == name }

    private fun renderSql(parts: JooqSearchParts): String =
        DSL
            .using(SQLDialect.POSTGRES)
            .select(parts.selectFields)
            .from(MATCH)
            .applyJoins(parts.joins)
            .where(parts.condition)
            .getSQL(ParamType.INLINED)

    @Test
    fun `translateById renders a plain equality condition for a root field, no EXISTS`() {
        // Given / When
        val id = UUID.randomUUID()
        val parts = translator.translateById(fieldByName("id"), id, mapping)
        val sql = renderSql(parts)

        // Then
        assertThat(sql.contains("\"match\".\"public_id\" = cast('$id' as uuid)"))
            .withFailMessage(sql)
            .isTrue()
        assertThat(Regex("(?i)exists").findAll(sql).count()).describedAs(sql).isEqualTo(0)
    }

    @Test
    fun `translateById has no ORDER BY`() {
        // Given / When
        val parts = translator.translateById(fieldByName("id"), UUID.randomUUID(), mapping)

        // Then
        assertThat(parts.orderBy).isEmpty()
    }

    @Test
    fun `translateById includes the same projection and joins as translate`() {
        // Given / When
        val parts = translator.translateById(fieldByName("id"), UUID.randomUUID(), mapping)

        // Then - the 1:1 "game" group's own JOIN and its field are still
        // part of the row shape, exactly like a findAll query - RowMapper
        // needs the full row regardless of how it was filtered down to one.
        assertThat(parts.joins.size).isEqualTo(1)
        // Every non-junction 1:1 group is now always aliased under its own
        // name, so the joined table is GAME.as("game"), not GAME itself -
        // compare by alias name instead of table identity.
        assertThat(parts.joins.any { it.table.name == "game" }).isTrue()
        val sql =
            DSL
                .using(SQLDialect.POSTGRES)
                .select(parts.selectFields)
                .from(MATCH)
                .getSQL(ParamType.INLINED)
        assertThat(sql.contains("gameName")).withFailMessage(sql).isTrue()
    }

    @Test
    fun `translateById combines the id condition with fixed default filters`() {
        // Given / When
        val id = UUID.randomUUID()
        val parts = translator.translateById(fieldByName("id"), id, mapping)
        val sql = renderSql(parts)

        // Then - the fixedValue(null) "deletedAt" field
        // still
        // applies unconditionally, same as a findAll query.
        assertThat(sql.contains("\"match\".\"public_id\" = cast('$id' as uuid)"))
            .withFailMessage(sql)
            .isTrue()
        assertThat(sql.contains("is null")).withFailMessage(sql).isTrue()
        assertThat(sql.contains(" and ")).withFailMessage(sql).isTrue()
    }

    @Test
    fun `translateById wraps a grouped field's condition in EXISTS`() {
        // Given / When
        val parts = translator.translateById(fieldByName("gameName"), "some-name", mapping)
        val sql = renderSql(parts)

        // Then
        assertThat(Regex("(?i)exists").containsMatchIn(sql)).withFailMessage(sql).isTrue()
        assertThat(sql.contains("'some-name'")).withFailMessage(sql).isTrue()
    }
}
