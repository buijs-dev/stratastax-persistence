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

import dev.buijs.stratastax.entity.core.api.EntityField
import dev.buijs.stratastax.entity.core.api.EntityFieldType
import dev.buijs.stratastax.entity.core.api.Sort
import dev.buijs.stratastax.entity.core.api.asc
import dev.buijs.stratastax.persistence.reactive.testfixtures.AnsiTestDialect
import dev.buijs.stratastax.search.core.api.AndExpression
import dev.buijs.stratastax.search.core.api.ComparisonExpression
import dev.buijs.stratastax.search.core.api.ComparisonOperator
import dev.buijs.stratastax.search.core.api.CursorDirection
import dev.buijs.stratastax.search.core.api.CursorState
import dev.buijs.stratastax.search.core.api.Filter
import dev.buijs.stratastax.search.core.api.FilterExpression
import dev.buijs.stratastax.search.core.api.FilterValue
import dev.buijs.stratastax.search.core.api.InExpression
import dev.buijs.stratastax.search.core.api.InOperator
import dev.buijs.stratastax.search.core.api.LikeExpression
import dev.buijs.stratastax.search.core.api.SearchQuery
import dev.buijs.stratastax.search.core.spi.CursorElement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Search rendering with [AnsiTestDialect], a dialect outside `persistence-postgres`: every piece of
 * syntax comes from the dialect, while the structure of the statement stays the same.
 */
class ForeignDialectRenderTest {

    private val idField = EntityField("id", EntityFieldType.UUID, sortable = true)

    private val nameField =
        EntityField("name", EntityFieldType.STRING, sortable = true, filterable = true)

    private val tagField = EntityField("tag", EntityFieldType.STRING, filterable = true)

    private val items =
        SearchSql(
            dialect = AnsiTestDialect,
            select = "SELECT item.id, item.name FROM item",
            countSelect = "SELECT count(*) AS count FROM item",
            fields =
                mapOf(
                    "id" to
                        SearchField(
                            type = SqlType.UUID,
                            target = SearchTarget.Column("item.id"),
                            sortColumn = "item.id",
                            neverNull = true,
                        ),
                    "name" to
                        SearchField(
                            type = SqlType.TEXT,
                            target = SearchTarget.Column("item.name"),
                            sortColumn = "item.name",
                            neverNull = true,
                        ),
                    "tag" to
                        SearchField(
                            type = SqlType.TEXT,
                            target =
                                SearchTarget.Related(
                                    exists =
                                        "EXISTS (SELECT 1 FROM item_tag tag_x " +
                                            "WHERE item.id = tag_x.item_id AND {condition})",
                                    column = "tag_x.name",
                                ),
                        ),
                ),
            defaultConditions = listOf("item.deleted_at IS NULL"),
            lockClause = "FOR UPDATE",
        )

    private val ada = UUID(0, 1)

    private fun search(
        filter: FilterExpression? = null,
        limit: Int = 50,
        cursor: CursorState? = null,
    ): SearchQuery =
        SearchQuery(
            limit = limit,
            sort = Sort(listOf(nameField.asc(), idField.asc())),
            filter = filter?.let(::Filter),
            cursor = cursor,
        )

    @Test
    fun `a page renders the dialect's placeholders, sort and limit`() {
        // given
        val query = search(limit = 2)

        // when
        val statement = items.pageQuery(query)

        // then
        assertThat(statement.sql).isEqualTo(
            "SELECT item.id, item.name FROM item" +
                "\nWHERE item.deleted_at IS NULL" +
                "\nORDER BY CASE WHEN item.name IS NULL THEN 1 ELSE 0 END, item.name ASC," +
                " CASE WHEN item.id IS NULL THEN 1 ELSE 0 END, item.id ASC" +
                "\nFETCH FIRST ? ROWS ONLY",
        )
        assertThat(statement.params).containsExactly(3)
    }

    @Test
    fun `several values are an IN list when the dialect binds no arrays`() {
        // given
        val values = listOf("a", "b").map(FilterValue::StringValue)
        val query = search(InExpression(nameField, InOperator.IN, values))
        val excluding = search(InExpression(nameField, InOperator.OUT, values))

        // when
        val statement = items.countQuery(query)
        val excluded = items.countQuery(excluding)

        // then
        assertThat(statement.sql).isEqualTo(
            "SELECT count(*) AS count FROM item\nWHERE item.name IN (?, ?)\n  AND item.deleted_at IS NULL",
        )
        assertThat(statement.params).containsExactly("a", "b")
        assertThat(excluded.sql).contains("WHERE item.name NOT IN (?, ?)")
    }

    @Test
    fun `a like and a condition on a relation use the dialect's syntax inside the generated fragments`() {
        // given
        val query =
            search(
                AndExpression(
                    listOf(
                        LikeExpression(nameField, "Fri%", ignoreCase = true),
                        ComparisonExpression(
                            tagField,
                            ComparisonOperator.EQUALS,
                            FilterValue.StringValue("x"),
                        ),
                    ),
                ),
            )

        // when
        val statement = items.countQuery(query)

        // then
        assertThat(statement.sql).contains(
            "(LOWER(item.name) LIKE LOWER(?) ESCAPE '\\'" +
                " AND EXISTS (SELECT 1 FROM item_tag tag_x WHERE item.id = tag_x.item_id AND tag_x.name = ?))",
        )
        assertThat(statement.params).containsExactly("Fri%", "x")
    }

    @Test
    fun `a cursor seek is spelled out per sort column when the dialect has no row values`() {
        // given
        val query =
            search(
                limit = 2,
                cursor =
                    CursorState(
                        listOf(
                            CursorElement.StringValue("b"),
                            CursorElement.StringValue(ada.toString()),
                        ),
                        hash = "",
                        direction = CursorDirection.AFTER,
                    ),
            )

        // when
        val statement = items.pageQuery(query)

        // then
        assertThat(statement.sql).contains(
            "\n  AND ((item.name > ? OR item.name IS NULL)" +
                " OR (item.name = ? AND (item.id > ? OR item.id IS NULL)))",
        )
        assertThat(statement.params).containsExactly("b", "b", ada, 3)
    }

    @Test
    fun `a seek past a null sort value uses the dialect's false condition`() {
        // given
        val query =
            search(
                cursor =
                    CursorState(
                        listOf(CursorElement.NullValue, CursorElement.StringValue(ada.toString())),
                        hash = "",
                        direction = CursorDirection.AFTER,
                    ),
            )

        // when
        val statement = items.pageQuery(query)

        // then
        assertThat(statement.sql).contains(
            "(1=0 OR (item.name IS NULL AND (item.id > ? OR item.id IS NULL)))",
        )
    }

    @Test
    fun `a lock query ends with the dialect's limit and skip-locked clause`() {
        // given
        val query = search(limit = 2)

        // when
        val statement = items.lockQuery(query)

        // then
        assertThat(statement.sql).endsWith("\nFETCH FIRST ? ROWS ONLY\nFOR UPDATE SKIP LOCKED")
        assertThat(statement.params).containsExactly(2)
    }
}
