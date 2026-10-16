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

import dev.buijs.stratastax.entity.core.api.Sort
import dev.buijs.stratastax.entity.core.api.SortDirection
import dev.buijs.stratastax.entity.core.api.SortField
import dev.buijs.stratastax.persistence.reactive.api.JooqField
import dev.buijs.stratastax.persistence.reactive.api.JooqFieldGroup
import dev.buijs.stratastax.persistence.reactive.api.JooqFieldMapping
import dev.buijs.stratastax.persistence.reactive.api.JooqSearchQueryTranslator
import dev.buijs.stratastax.persistence.reactive.api.applyJoins
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.idField
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.lastEditedByUserDisplayNameField
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.recorderUserDisplayNameField
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.recorderUserTeamNameField
import dev.buijs.stratastax.search.core.api.CursorDirection
import dev.buijs.stratastax.search.core.api.CursorState
import dev.buijs.stratastax.search.core.api.SearchException
import dev.buijs.stratastax.search.core.api.SearchQuery
import dev.buijs.stratastax.search.core.spi.CursorElement
import dev.buijs.stratastax.search.core.spi.FieldGroupRelation
import dev.buijs.stratastax.search.core.spi.StandardSearchConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.Record
import org.jooq.SQLDialect
import org.jooq.TableField
import org.jooq.conf.ParamType
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.impl.TableImpl
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Regression coverage for a real reported bug: a domain entity with *two* `oneToOneGroup<T>`
 * relations to the *same* underlying table (e.g. a `recorderUser`/`lastEditedByUser`-style pair
 * both pointing at `APP_USER`) used to render an ambiguous, invalid duplicate `join APP_USER on
 * ...` / `join APP_USER on ...`. Every non-junction 1:1 group is now always aliased under its own
 * already-unique [JooqFieldGroup.name], and a join is rendered as `LEFT JOIN` whenever the
 * relation's own FK column is nullable (an un-set nullable relation must not drop the whole row).
 *
 * Deliberately self-contained fake tables/domain types (not the shared
 * `testfixtures.MATCH`/`MatchEntity`) so this scenario - two groups to one table - doesn't have to
 * be retrofitted into fixtures many other test files already depend on.
 */
internal class JooqSearchQueryTranslatorAliasTest {

    // Named like the jOOQ-generated table it stands in for.
    @Suppress("ktlint:standard:class-naming")
    private object ROOT_TABLE : TableImpl<Record>(DSL.name("root_table")) {

        val ID: TableField<Record, UUID> =
            createField(DSL.name("id"), SQLDataType.UUID.nullable(false))
        val RECORDER_USER_ID: TableField<Record, UUID> =
            createField(DSL.name("recorder_user_id"), SQLDataType.UUID.nullable(false))
        val LAST_EDITED_BY_USER_ID: TableField<Record, UUID> =
            createField(DSL.name("last_edited_by_user_id"), SQLDataType.UUID)
    }

    // Named like the jOOQ-generated table it stands in for.
    @Suppress("ktlint:standard:class-naming")
    private object APP_USER : TableImpl<Record>(DSL.name("app_user")) {

        val ID: TableField<Record, UUID> =
            createField(DSL.name("id"), SQLDataType.UUID.nullable(false))
        val DISPLAY_NAME: TableField<Record, String> =
            createField(DSL.name("display_name"), SQLDataType.VARCHAR)
        val TEAM_ID: TableField<Record, UUID> =
            createField(DSL.name("team_id"), SQLDataType.UUID.nullable(false))
    }

    private object TEAM : TableImpl<Record>(DSL.name("team")) {

        val ID: TableField<Record, UUID> =
            createField(DSL.name("id"), SQLDataType.UUID.nullable(false))
        val NAME: TableField<Record, String> = createField(DSL.name("name"), SQLDataType.VARCHAR)
    }

    private val translator = JooqSearchQueryTranslator()

    private val configuration =
        StandardSearchConfiguration(
            defaultSort = listOf(SortField(idField, SortDirection.ASC)),
            fields =
                listOf(
                    idField,
                    recorderUserDisplayNameField,
                    recorderUserTeamNameField,
                    lastEditedByUserDisplayNameField,
                ),
            tiebreakerField = idField,
        )
    val mapping =
        JooqFieldMapping(
            fields =
                mapOf(
                    "id" to JooqField(field = ROOT_TABLE.ID, selectable = true),
                    "recorderUserDisplayName" to
                        JooqField(field = APP_USER.DISPLAY_NAME, selectable = true),
                    "recorderUserTeamName" to JooqField(field = TEAM.NAME, selectable = true),
                    "lastEditedByUserDisplayName" to
                        JooqField(field = APP_USER.DISPLAY_NAME, selectable = true),
                    "lastEditedByUserTeamName" to JooqField(field = TEAM.NAME, selectable = true),
                ),
            groups =
                listOf(
                    JooqFieldGroup(
                        name = "recorderUser",
                        relation = FieldGroupRelation.OneToOne,
                        from = ROOT_TABLE.RECORDER_USER_ID,
                        to = APP_USER.ID,
                        fieldNames = listOf("recorderUserDisplayName"),
                    ),
                    JooqFieldGroup(
                        name = "recorderUserTeam",
                        relation = FieldGroupRelation.OneToOne,
                        from = APP_USER.TEAM_ID,
                        to = TEAM.ID,
                        parentName = "recorderUser",
                        fieldNames = listOf("recorderUserTeamName"),
                    ),
                    JooqFieldGroup(
                        name = "lastEditedByUser",
                        relation = FieldGroupRelation.OneToOne,
                        from = ROOT_TABLE.LAST_EDITED_BY_USER_ID,
                        to = APP_USER.ID,
                        fieldNames = listOf("lastEditedByUserDisplayName"),
                    ),
                    // A NOT NULL FK, but nested inside the optional
                    // "lastEditedByUser".
                    JooqFieldGroup(
                        name = "lastEditedByUserTeam",
                        relation = FieldGroupRelation.OneToOne,
                        from = APP_USER.TEAM_ID,
                        to = TEAM.ID,
                        parentName = "lastEditedByUser",
                        fieldNames = listOf("lastEditedByUserTeamName"),
                    ),
                ),
        )

    private fun fieldByName(name: String) = configuration.fields.first { it.name == name }

    private fun renderedSql(sort: List<SortField> = emptyList()): String {
        val query =
            SearchQuery(
                limit = 20,
                sort =
                    Sort(sort.ifEmpty { listOf(SortField(fieldByName("id"), SortDirection.ASC)) }),
                filter = null,
                cursor = null,
            )
        val parts = translator.translate(query, mapping)
        return DSL
            .using(SQLDialect.POSTGRES)
            .select(parts.selectFields)
            .from(ROOT_TABLE)
            .applyJoins(parts.joins)
            .getSQL(ParamType.INLINED)
    }

    @Test
    fun `two groups joining the same physical table get two distinct aliases`() {
        // When
        val sql = renderedSql()

        // Then - both aliases present, the raw table name never appears
        // un-aliased/ambiguous in the FROM/JOIN clause.
        assertThat(sql.contains("app_user\" as \"recorderUser\"")).withFailMessage(sql).isTrue()
        assertThat(sql.contains("app_user\" as \"lastEditedByUser\"")).withFailMessage(sql).isTrue()
    }

    @Test
    fun `select list references each alias distinctly, not the raw table`() {
        // When
        val sql = renderedSql()

        // Then
        assertThat(sql.contains("\"recorderUser\".\"display_name\"")).withFailMessage(sql).isTrue()
        assertThat(sql.contains("\"lastEditedByUser\".\"display_name\""))
            .withFailMessage(sql)
            .isTrue()
    }

    @Test
    fun `a not-null FK relation renders a plain JOIN`() {
        // When
        val sql = renderedSql()

        // Then - "recorderUser" (NOT NULL FK) is a plain join directly off
        // the root table, not a "left outer join" (jOOQ's own rendering of
        // LEFT JOIN).
        assertThat(sql.contains("\"root_table\" join \"app_user\" as \"recorderUser\""))
            .withFailMessage(sql)
            .isTrue()
        assertThat(sql.contains("left outer join \"app_user\" as \"recorderUser\""))
            .withFailMessage(sql)
            .isFalse()
    }

    @Test
    fun `a nullable FK relation renders a LEFT JOIN`() {
        // When
        val sql = renderedSql()

        // Then - "lastEditedByUser" (nullable FK) must not drop the whole
        // row when unset - jOOQ renders LEFT JOIN as "left outer join".
        assertThat(sql.contains("left outer join \"app_user\" as \"lastEditedByUser\""))
            .withFailMessage(sql)
            .isTrue()
    }

    @Test
    fun `a not-null FK relation nested inside a LEFT JOINed parent renders a LEFT JOIN too`() {
        // When
        val sql = renderedSql()

        // Then - "lastEditedByUserTeam" has a NOT NULL FK, but an unset
        // "lastEditedByUser" must still not drop the whole row through it.
        assertThat(sql.contains("left outer join \"team\" as \"lastEditedByUserTeam\""))
            .withFailMessage(sql)
            .isTrue()
        // Below a plain JOIN it stays a plain JOIN.
        assertThat(sql.contains("left outer join \"team\" as \"recorderUserTeam\""))
            .withFailMessage(sql)
            .isFalse()
        assertThat(sql.contains("join \"team\" as \"recorderUserTeam\""))
            .withFailMessage(sql)
            .isTrue()
    }

    @Test
    fun `a group nested inside an aliased 1-1 parent joins against the parent's alias`() {
        // When - "team" is nested inside "recorderUser", so it gets its own
        // alias too ("recorderUserTeam") - its join CONDITION must read the
        // FK off the *parent's* alias ("recorderUser"), not the raw,
        // out-of-scope "app_user" table name.
        val sql = renderedSql()

        // Then
        assertThat(sql.contains("\"recorderUser\".\"team_id\" = \"recorderUserTeam\".\"id\""))
            .withFailMessage(sql)
            .isTrue()
        assertThat(sql.contains("\"app_user\".\"team_id\"")).withFailMessage(sql).isFalse()
    }

    @Test
    fun `sorting by a field on an aliased group renders ORDER BY against that alias`() {
        // Given
        val sortField = SortField(fieldByName("recorderUserDisplayName"), SortDirection.ASC)
        val query =
            SearchQuery(limit = 20, sort = Sort(listOf(sortField)), filter = null, cursor = null)

        // When
        val parts = translator.translate(query, mapping)
        val orderSql = DSL.using(SQLDialect.POSTGRES).render(parts.orderBy[0])

        // Then
        assertThat(orderSql.contains("\"recorderUser\".\"display_name\""))
            .withFailMessage(orderSql)
            .isTrue()
    }

    @Test
    fun `a cursor on an aliased group field seeks against that alias, NULL as the largest value`() {
        val sort =
            Sort(
                listOf(
                    SortField(fieldByName("recorderUserDisplayName"), SortDirection.ASC),
                    SortField(fieldByName("id"), SortDirection.ASC),
                ),
            )
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val query =
            SearchQuery(
                limit = 20,
                sort = sort,
                filter = null,
                cursor =
                    CursorState(
                        listOf(CursorElement.StringValue("Ann"), CursorElement.StringValue("$id")),
                        "hash",
                    ),
            )

        val parts = translator.translate(query, mapping)
        val dsl = DSL.using(SQLDialect.POSTGRES)
        val seekSql = dsl.renderInlined(parts.seekCondition!!)

        assertThat(seekSql)
            .isEqualTo(
                "(\"recorderUser\".\"display_name\" > 'Ann' or \"recorderUser\".\"display_name\" " +
                    "is null " +
                    "or (\"recorderUser\".\"display_name\" = 'Ann' and (\"root_table\".\"id\" > " +
                    "cast('$id' as uuid) " +
                    "or \"root_table\".\"id\" is null)))",
            )
        assertThat(dsl.render(parts.orderBy[0])).endsWith("asc nulls last")
        // The seek is never part of what a count counts.
        assertThat(parts.condition).isNull()
    }

    @Test
    fun `a cursor for the previous page reads the order backwards`() {
        val sort =
            Sort(
                listOf(
                    SortField(fieldByName("recorderUserDisplayName"), SortDirection.DESC),
                    SortField(fieldByName("id"), SortDirection.ASC),
                ),
            )
        val query =
            SearchQuery(
                limit = 20,
                sort = sort,
                filter = null,
                cursor =
                    CursorState(
                        listOf(CursorElement.NullValue, CursorElement.StringValue("${UUID(0, 1)}")),
                        "hash",
                        CursorDirection.BEFORE,
                    ),
            )

        val parts = translator.translate(query, mapping)
        val dsl = DSL.using(SQLDialect.POSTGRES)

        assertThat(parts.orderBy.map { dsl.render(it) })
            .containsExactly(
                "\"recorderUser\".\"display_name\" asc nulls last",
                "\"root_table\".\"id\" desc nulls first",
            )
        // Descending puts NULLs first: before a NULL lie only the NULLs with a lower tiebreaker.
        assertThat(dsl.renderInlined(parts.seekCondition!!))
            .isEqualTo(
                "(false or (\"recorderUser\".\"display_name\" is null and " +
                    "\"root_table\".\"id\" < cast('${UUID(0, 1)}' as uuid)))",
            )
    }

    @Test
    fun `a cursor with another number of values than sort fields is refused`() {
        val query =
            SearchQuery(
                limit = 20,
                sort = Sort(listOf(SortField(fieldByName("id"), SortDirection.ASC))),
                filter = null,
                cursor = CursorState(emptyList(), "hash"),
            )

        assertThatThrownBy { translator.translate(query, mapping) }
            .isInstanceOf(SearchException::class.java)
    }
}
