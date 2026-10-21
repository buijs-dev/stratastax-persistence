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
import dev.buijs.stratastax.entity.core.api.asc
import dev.buijs.stratastax.entity.core.api.desc
import dev.buijs.stratastax.entity.core.spi.UnknownFieldException
import dev.buijs.stratastax.persistence.reactive.sqlfirst.PlaysetSearchSql
import dev.buijs.stratastax.persistence.reactive.sqlfirst.PlaysetSql
import dev.buijs.stratastax.persistence.reactive.testfixtures.PlaysetModel
import dev.buijs.stratastax.persistence.reactive.testfixtures.Searches.after
import dev.buijs.stratastax.persistence.reactive.testfixtures.Searches.before
import dev.buijs.stratastax.persistence.reactive.testfixtures.Searches.eq
import dev.buijs.stratastax.persistence.reactive.testfixtures.Searches.isOut
import dev.buijs.stratastax.persistence.reactive.testfixtures.Searches.search
import dev.buijs.stratastax.search.core.api.AndExpression
import dev.buijs.stratastax.search.core.api.ComparisonExpression
import dev.buijs.stratastax.search.core.api.ComparisonOperator
import dev.buijs.stratastax.search.core.api.FilterValue
import dev.buijs.stratastax.search.core.api.InExpression
import dev.buijs.stratastax.search.core.api.InOperator
import dev.buijs.stratastax.search.core.api.LikeExpression
import dev.buijs.stratastax.search.core.api.OrExpression
import dev.buijs.stratastax.search.core.api.SearchException
import dev.buijs.stratastax.search.core.spi.CursorElement
import io.vertx.core.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Rendering a search over generated fragments ([SearchSql]): what text and parameters the filter,
 * the sort, the cursor and the limit become.
 */
internal class SearchSqlRenderTest {

    private val playsets = PlaysetSearchSql.sql

    private val byName = listOf(PlaysetModel.nameField.asc(), PlaysetModel.idField.asc())

    private val byGame = listOf(PlaysetModel.gameNameField.asc(), PlaysetModel.idField.asc())

    private val ada = UUID(0, 1)

    @Test
    fun `a page without filter applies the default conditions, the sort and one row more than the limit`() {
        // given
        val query = search(limit = 2)

        // when
        val statement = playsets.pageQuery(query)

        // then
        assertThat(statement.sql)
            .startsWith(PlaysetSql.SELECT)
            .endsWith(
                "\nWHERE playset.deleted_at IS NULL" +
                    "\nORDER BY playset.name ASC NULLS LAST, playset.public_id ASC NULLS LAST" +
                    "\nLIMIT \$1::integer",
            )
        assertThat(statement.params).containsExactly(3)
    }

    @Test
    fun `a comparison binds its value cast to the column type`() {
        // given
        val query = search(eq(PlaysetModel.nameField, "Friday"))

        // when
        val statement = playsets.countQuery(query)

        // then
        assertThat(statement.sql).isEqualTo(
            "SELECT count(*) AS count FROM playset\nWHERE playset.name = \$1::varchar\n  AND playset.deleted_at IS NULL",
        )
        assertThat(statement.params).containsExactly("Friday")
    }

    @Test
    fun `a condition that matches null on a one-to-one field also matches rows without the related row`() {
        // given
        val query = search(eq(PlaysetModel.gameNameField, null))

        // when
        val statement = playsets.countQuery(query)

        // then
        assertThat(statement.sql).contains(
            "(EXISTS (SELECT 1 FROM game game_x WHERE playset.game_id = game_x.id" +
                " AND game_x.name IS NULL)" +
                " OR NOT EXISTS (SELECT 1 FROM game game_x WHERE playset.game_id = game_x.id))",
        )
        assertThat(statement.params).isEmpty()
    }

    @Test
    fun `a condition on a one-to-many field is an EXISTS, so it never multiplies the rows`() {
        // given
        val query = search(eq(PlaysetModel.notesTextField, "first"))

        // when
        val statement = playsets.countQuery(query)

        // then
        assertThat(statement.sql).contains(
            "EXISTS (SELECT 1 FROM playset_note notes_x WHERE playset.id = notes_x.playset_id" +
                " AND notes_x.deleted_at IS NULL AND notes_x.text = \$1::varchar)",
        )
        assertThat(statement.sql).doesNotContain("JOIN playset_note")
    }

    @Test
    fun `IN binds several values as one typed array and matches null on its own`() {
        // given
        val ids = listOf(UUID(0, 1), UUID(0, 2))
        val query =
            search(
                InExpression(
                    PlaysetModel.idField,
                    InOperator.IN,
                    ids.map(FilterValue::UuidValue) + FilterValue.NullValue,
                ),
            )

        // when
        val statement = playsets.countQuery(query)

        // then
        assertThat(
            statement.sql,
        ).contains("(playset.public_id = ANY(\$1::uuid[]) OR playset.public_id IS NULL)")
        assertThat(
            statement.params.single(),
        ).isInstanceOf(Array<UUID>::class.java).isEqualTo(ids.toTypedArray())
    }

    @Test
    fun `OUT with a null among the values leaves out NULL too`() {
        // given
        val query = search(isOut(PlaysetModel.nameField, "a", null))

        // when
        val statement = playsets.countQuery(query)

        // then
        assertThat(
            statement.sql,
        ).contains("(playset.name NOT IN (\$1::varchar) AND playset.name IS NOT NULL)")
        assertThat(statement.params).containsExactly("a")
    }

    @Test
    fun `LIKE keeps the pattern a parameter, escaped with a backslash`() {
        // given
        val query =
            search(
                OrExpression(
                    listOf(
                        LikeExpression(PlaysetModel.nameField, "Fri%", ignoreCase = true),
                        LikeExpression(PlaysetModel.nameField, "%day"),
                    ),
                ),
            )

        // when
        val statement = playsets.countQuery(query)

        // then
        assertThat(statement.sql).contains(
            "(playset.name ILIKE \$1::varchar ESCAPE '\\' OR playset.name LIKE \$2::varchar ESCAPE '\\')",
        )
        assertThat(statement.params).containsExactly("Fri%", "%day")
    }

    @Test
    fun `a cursor on NOT NULL columns sorted the same way is one row-value comparison`() {
        // given
        val query =
            search(
                sort = byName,
                cursor =
                    after(
                        CursorElement.StringValue("b"),
                        CursorElement.StringValue(ada.toString()),
                    ),
            )

        // when
        val statement = playsets.pageQuery(query)

        // then
        assertThat(statement.sql).contains(
            "WHERE playset.deleted_at IS NULL\n  AND (playset.name, playset.public_id) > (\$1::varchar, \$2::uuid)",
        )
        assertThat(statement.params).containsExactly("b", ada, 51)
    }

    @Test
    fun `a cursor on a nullable sort column is written out, NULL sorting as the largest value`() {
        // given
        val query =
            search(
                sort = byGame,
                cursor = after(CursorElement.NullValue, CursorElement.StringValue(ada.toString())),
            )

        // when
        val statement = playsets.pageQuery(query)

        // then
        assertThat(statement.sql).contains(
            "(FALSE OR (game.name IS NULL AND (playset.public_id > \$1::uuid OR playset.public_id IS NULL)))",
        )
        assertThat(statement.params).containsExactly(ada, 51)
    }

    @Test
    fun `a page before a cursor reads the sort backwards`() {
        // given
        val query =
            search(
                sort = byName,
                cursor =
                    before(
                        CursorElement.StringValue("b"),
                        CursorElement.StringValue(ada.toString()),
                    ),
            )

        // when
        val statement = playsets.pageQuery(query)

        // then
        assertThat(statement.sql)
            .contains("(playset.name, playset.public_id) < (\$1::varchar, \$2::uuid)")
            .contains("ORDER BY playset.name DESC NULLS FIRST, playset.public_id DESC NULLS FIRST")
    }

    @Test
    fun `a scope restricts on top of the filter and a count leaves out sort, cursor and limit`() {
        // given
        val query =
            search(
                eq(PlaysetModel.nameField, "a"),
                sort = byName,
                cursor =
                    after(
                        CursorElement.StringValue("b"),
                        CursorElement.StringValue(ada.toString()),
                    ),
            )
        val scope = eq(PlaysetModel.ownerNameField, "Ada")

        // when
        val statement = playsets.countQuery(query, scope)

        // then
        assertThat(statement.sql).isEqualTo(
            "SELECT count(*) AS count FROM playset" +
                "\nWHERE playset.name = \$1::varchar" +
                "\n  AND EXISTS (SELECT 1 FROM app_user owner_x" +
                " WHERE playset.owner_id = owner_x.id" +
                " AND owner_x.display_name = \$2::varchar)" +
                "\n  AND playset.deleted_at IS NULL",
        )
        assertThat(statement.params).containsExactly("a", "Ada")
    }

    @Test
    fun `a list ignores the limit and the cursor, and a lock query locks without a look-ahead row`() {
        // given
        val query =
            search(
                limit = 2,
                cursor =
                    after(
                        CursorElement.StringValue("b"),
                        CursorElement.StringValue(ada.toString()),
                    ),
            )

        // when
        val list = playsets.listQuery(query, select = "SELECT playset.name AS name FROM playset")
        val skipping = playsets.lockQuery(query)
        val waiting = playsets.lockQuery(query, skipLocked = false)

        // then
        assertThat(list.sql).isEqualTo(
            "SELECT playset.name AS name FROM playset\nWHERE playset.deleted_at IS NULL" +
                "\nORDER BY playset.name ASC NULLS LAST, playset.public_id ASC NULLS LAST",
        )
        assertThat(skipping.sql).endsWith("\nLIMIT \$3::integer\nFOR UPDATE OF playset SKIP LOCKED")
        assertThat(skipping.params).containsExactly("b", ada, 2)
        assertThat(waiting.sql).endsWith("\nLIMIT \$3::integer\nFOR UPDATE OF playset")
    }

    @Test
    fun `AND joins its conditions and OR groups them`() {
        // given
        val a =
            ComparisonExpression(
                PlaysetModel.nameField,
                ComparisonOperator.EQUALS,
                FilterValue.StringValue("a"),
            )
        val b =
            ComparisonExpression(
                PlaysetModel.nameField,
                ComparisonOperator.EQUALS,
                FilterValue.StringValue("b"),
            )

        // when
        val and = playsets.countQuery(search(AndExpression(listOf(a, b))))
        val or = playsets.countQuery(search(OrExpression(listOf(a, b))))

        // then
        assertThat(
            and.sql,
        ).contains("WHERE (playset.name = \$1::varchar AND playset.name = \$2::varchar)")
        assertThat(
            or.sql,
        ).contains("WHERE (playset.name = \$1::varchar OR playset.name = \$2::varchar)")
        assertThat(and.params).containsExactly("a", "b")
    }

    @Test
    fun `a JSON field is compared with a jsonb parameter`() {
        // given
        val attributes = EntityField("attributes", EntityFieldType.JSON, filterable = true)
        val query =
            search(
                ComparisonExpression(
                    attributes,
                    ComparisonOperator.EQUALS,
                    FilterValue.JsonValue("""{"players": 4}"""),
                ),
            )

        // when
        val statement = playsets.countQuery(query)

        // then
        assertThat(statement.sql).contains("WHERE playset.attributes = \$1::jsonb")
        assertThat(statement.params).containsExactly(JsonObject().put("players", 4))
    }

    @Test
    fun `a page before a cursor seeks backwards field by field when the directions are mixed`() {
        // given
        val query =
            search(
                sort = listOf(PlaysetModel.nameField.asc(), PlaysetModel.createdAtField.desc()),
                cursor = before(CursorElement.StringValue("m"), CursorElement.NullValue),
            )

        // when
        val statement = playsets.pageQuery(query)

        // then - backwards, name is read DESC and created_at ASC; with NULL as the largest value,
        // nothing lies beyond a NULL created_at
        assertThat(statement.sql)
            .contains(
                "AND (playset.name < \$1::varchar OR (playset.name = \$2::varchar AND FALSE))",
            ).contains("ORDER BY playset.name DESC NULLS FIRST, playset.created_at ASC NULLS LAST")
        assertThat(statement.params).containsExactly("m", "m", 51)
    }

    @Test
    fun `a sort on a one-to-many field is rejected`() {
        // given
        val query = search(sort = listOf(PlaysetModel.notesTextField.asc()))

        // when
        val thrown = catchThrowable { playsets.pageQuery(query) }

        // then
        assertThat(thrown)
            .isInstanceOf(SearchException::class.java)
            .hasMessage(
                "Field 'notesText' belongs to a one-to-many relation and cannot be sorted on",
            )
    }

    @Test
    fun `a cursor for another sort and a null for an ordering operator are rejected`() {
        // given
        val otherSort = search(sort = byName, cursor = after(CursorElement.StringValue("b")))
        val greaterThanNull =
            search(
                ComparisonExpression(
                    PlaysetModel.nameField,
                    ComparisonOperator.GREATER_THAN,
                    FilterValue.NullValue,
                ),
            )

        // when
        val wrongCursor = catchThrowable { playsets.pageQuery(otherSort) }

        val nullValue = catchThrowable { playsets.pageQuery(greaterThanNull) }

        // then
        assertThat(
            wrongCursor,
        ).isInstanceOf(
            SearchException::class.java,
        ).hasMessage("Cursor does not match the current sort criteria")
        assertThat(nullValue)
            .isInstanceOf(SearchException::class.java)
            .hasMessage("Operator 'GREATER_THAN' on field 'name' does not accept null")
    }

    @Test
    fun `a field the entity does not have is rejected`() {
        // given
        val query = search(eq(EntityField("unknown", EntityFieldType.STRING), "x"))

        // when
        val thrown = catchThrowable { playsets.countQuery(query) }

        // then
        assertThat(thrown).isInstanceOf(UnknownFieldException::class.java)
    }

    @Test
    fun `a related target without the condition placeholder is rejected`() {
        // given
        val exists = "EXISTS (SELECT 1 FROM game game_x WHERE playset.game_id = game_x.id)"

        // when
        val thrown = catchThrowable { SearchTarget.Related(exists, "game_x.name") }

        // then
        assertThat(
            thrown,
        ).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("{condition}")
    }
}
