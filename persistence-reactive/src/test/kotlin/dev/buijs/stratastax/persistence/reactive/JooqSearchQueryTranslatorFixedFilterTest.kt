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

import dev.buijs.stratastax.entity.core.api.EntityField
import dev.buijs.stratastax.entity.core.api.EntityFieldType
import dev.buijs.stratastax.entity.core.api.Sort
import dev.buijs.stratastax.entity.core.api.SortDirection
import dev.buijs.stratastax.entity.core.api.SortField
import dev.buijs.stratastax.persistence.reactive.api.JooqField
import dev.buijs.stratastax.persistence.reactive.api.JooqFieldGroup
import dev.buijs.stratastax.persistence.reactive.api.JooqFieldMapping
import dev.buijs.stratastax.persistence.reactive.api.JooqSearchQueryTranslator
import dev.buijs.stratastax.persistence.reactive.testfixtures.GAME
import dev.buijs.stratastax.persistence.reactive.testfixtures.MATCH
import dev.buijs.stratastax.search.core.api.ComparisonExpression
import dev.buijs.stratastax.search.core.api.ComparisonOperator
import dev.buijs.stratastax.search.core.api.Filter
import dev.buijs.stratastax.search.core.api.FilterValue
import dev.buijs.stratastax.search.core.api.SearchQuery
import dev.buijs.stratastax.search.core.spi.FieldGroupRelation
import dev.buijs.stratastax.search.core.spi.StandardSearchConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test

internal class JooqSearchQueryTranslatorFixedFilterTest {

    private val translator = JooqSearchQueryTranslator()

    private val idField =
        EntityField(name = "id", type = EntityFieldType.UUID, sortable = true, filterable = true)
    private val statusField =
        EntityField(
            name = "status",
            type = EntityFieldType.STRING,
            sortable = true,
            filterable = true,
        )
    private val playedAtField = EntityField(name = "playedAt", type = EntityFieldType.DATETIME)
    private val gameNameField =
        EntityField(
            name = "gameName",
            type = EntityFieldType.STRING,
            sortable = true,
            filterable = true,
        )
    private val gameIdField = EntityField(name = "gameId", type = EntityFieldType.UUID)

    private val configuration =
        StandardSearchConfiguration(
            defaultSort = listOf(SortField(idField, SortDirection.ASC)),
            fields = listOf(idField, statusField, playedAtField, gameNameField, gameIdField),
            tiebreakerField = idField,
        )

    private val mapping =
        JooqFieldMapping(
            fields =
                mapOf(
                    "id" to JooqField(field = MATCH.PUBLIC_ID, selectable = true),
                    "status" to JooqField(field = MATCH.STATUS, selectable = true),
                    "playedAt" to
                        JooqField(
                            field = MATCH.PLAYED_AT,
                            selectable = true,
                            defaultFilterValue = FilterValue.NullValue,
                        ),
                    "gameName" to JooqField(field = GAME.NAME, selectable = true),
                    "gameId" to
                        JooqField(
                            field = GAME.PUBLIC_ID,
                            selectable = true,
                            defaultFilterValue = FilterValue.NullValue,
                        ),
                ),
            groups =
                listOf(
                    JooqFieldGroup(
                        name = "game",
                        relation = FieldGroupRelation.OneToOne,
                        from = MATCH.ID,
                        to = GAME.ID,
                        fieldNames = listOf("gameName", "gameId"),
                    ),
                ),
        )

    private fun fieldByName(name: String) = configuration.fields.first { it.name == name }

    private fun queryFor(filter: Filter?): SearchQuery =
        SearchQuery(
            limit = 20,
            sort = Sort(listOf(SortField(fieldByName("id"), SortDirection.ASC))),
            filter = filter,
            cursor = null,
        )

    private fun renderCondition(query: SearchQuery): String {
        val parts = translator.translate(query, mapping)
        return parts.condition?.let { DSL.using(SQLDialect.POSTGRES).render(it) } ?: ""
    }

    @Test
    fun `a fixed field renders its condition even with no caller filter at all`() {
        // Given / When
        val sql = renderCondition(queryFor(null))

        // Then
        assertThat(sql.contains("is null")).withFailMessage(sql).isTrue()
        assertThat(sql.contains("played_at")).withFailMessage(sql).isTrue()
    }

    @Test
    fun `a fixed field's condition is combined with the caller's own filter`() {
        // Given
        val expr =
            ComparisonExpression(
                fieldByName("status"),
                ComparisonOperator.EQUALS,
                FilterValue.StringValue("ACTIVE"),
            )

        // When
        val sql = renderCondition(queryFor(Filter(expr)))

        // Then
        assertThat(sql.contains("status")).withFailMessage(sql).isTrue()
        assertThat(sql.contains("played_at")).withFailMessage(sql).isTrue()
        assertThat(sql.contains("is null")).withFailMessage(sql).isTrue()
        assertThat(sql.contains(" and ")).withFailMessage(sql).isTrue()
    }

    @Test
    fun `a fixed field inside a 1 to 1 group renders wrapped in EXISTS`() {
        // Given / When
        val sql = renderCondition(queryFor(null))

        // Then - "deletedAt" belongs to the "game" group, so its fixed
        // condition must be wrapped in an EXISTS against GAME, same as a
        // caller filter on a grouped field already is.
        assertThat(Regex("(?i)exists").containsMatchIn(sql)).withFailMessage(sql).isTrue()
        assertThat(sql.contains("game", ignoreCase = true)).withFailMessage(sql).isTrue()
    }
}
