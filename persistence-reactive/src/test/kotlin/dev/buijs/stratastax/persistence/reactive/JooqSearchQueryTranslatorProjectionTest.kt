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
import dev.buijs.stratastax.persistence.reactive.testfixtures.GAME
import dev.buijs.stratastax.persistence.reactive.testfixtures.MATCH
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.gameNameField
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.idField
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.playedAtField
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.statusField
import dev.buijs.stratastax.search.core.api.Filter
import dev.buijs.stratastax.search.core.api.FilterValue
import dev.buijs.stratastax.search.core.api.SearchQuery
import dev.buijs.stratastax.search.core.api.containsIgnoreCase
import dev.buijs.stratastax.search.core.spi.FieldGroupRelation
import dev.buijs.stratastax.search.core.spi.StandardSearchConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.jooq.SQLDialect
import org.jooq.conf.ParamType
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test

/**
 * Covers the narrowed, projection-scoped `translate(query, mapping, fields)` overload - the
 * query-translator half of a `@DomainEntityProjection`-declared `projection<P>(...)` (see
 * `entity-core`'s `DomainEntityProjection` annotation's own kdoc). Reuses the exact fixture shape
 * [JooqSearchQueryTranslatorFixedFilterTest]/[JooqSearchQueryTranslatorGroupTest] already establish
 * (`MatchEntity`/`GameRef`/`MATCH`/`GAME`).
 */
internal class JooqSearchQueryTranslatorProjectionTest {

    private val translator = JooqSearchQueryTranslator()

    private val configuration =
        StandardSearchConfiguration(
            defaultSort = listOf(SortField(idField, SortDirection.ASC)),
            fields = listOf(idField, statusField, playedAtField, gameNameField),
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

    private fun queryFor(sortField: String = "id"): SearchQuery =
        SearchQuery(
            limit = 20,
            sort = Sort(listOf(SortField(fieldByName(sortField), SortDirection.ASC))),
            filter = null,
            cursor = null,
        )

    @Test
    fun `selectFields is narrowed to only the requested fields`() {
        // Given / When
        val parts = translator.translate(queryFor(), mapping, fields = setOf("id", "status"))
        val sql =
            DSL.using(SQLDialect.POSTGRES).select(parts.selectFields).getSQL(ParamType.INLINED)

        // Then
        assertThat(parts.selectFields.size).isEqualTo(2)
        assertThat(sql.contains("public_id", ignoreCase = true)).isTrue()
        assertThat(sql.contains("status", ignoreCase = true)).isTrue()
        assertThat(sql.contains("played_at", ignoreCase = true)).isFalse()
        assertThat(sql.contains("\"game\"", ignoreCase = true)).isFalse()
    }

    @Test
    fun `a fixed field's condition still applies even when not among the requested fields`() {
        // Given - "playedAt" carries a fixedValue(NullValue) but isn't
        // requested for selection at all.
        val parts = translator.translate(queryFor(), mapping, fields = setOf("id", "status"))
        val sql = parts.condition?.let { DSL.using(SQLDialect.POSTGRES).render(it) } ?: ""

        // Then - the default filter is a WHERE-clause concern, entirely
        // independent of what ends up in the SELECT list.
        assertThat(sql.contains("played_at", ignoreCase = true)).withFailMessage(sql).isTrue()
        assertThat(sql.contains("is null")).isTrue()
    }

    @Test
    fun `sorting by a 1 to 1 group field still joins it even though it is not selected`() {
        // Given - the projection only selects "id"/"status", but the caller's
        // own sort{} targets the 1:1 group's own "gameName" field.
        val parts =
            translator.translate(
                queryFor(sortField = "gameName"),
                mapping,
                fields = setOf("id", "status"),
            )

        // Then - the ORDER BY resolves against the joined "game" alias, and
        // that join is actually present (this is the whole point: a narrowed
        // projection must not produce an ORDER BY referencing a table that
        // was never joined).
        assertThat(parts.orderBy.size).isEqualTo(1)
        val orderSql = DSL.using(SQLDialect.POSTGRES).render(parts.orderBy[0])
        assertThat(orderSql.contains("name", ignoreCase = true)).isTrue()
        assertThat(parts.joins.any { it.table.name == "game" }).isTrue()

        // And "game"'s own columns are still absent from the narrowed select
        // list - the join exists purely to make the ORDER BY valid, not to
        // fetch the group's own data.
        val sql =
            DSL.using(SQLDialect.POSTGRES).select(parts.selectFields).getSQL(ParamType.INLINED)
        assertThat(sql.contains("\"game\"", ignoreCase = true)).isFalse()
    }

    @Test
    fun `a requested 1 to 1 group field is selected under its qualified alias`() {
        // Given / When
        val parts = translator.translate(queryFor(), mapping, fields = setOf("id", "gameName"))
        val sql =
            DSL.using(SQLDialect.POSTGRES).select(parts.selectFields).getSQL(ParamType.INLINED)

        // Then - read from the joined "game" alias, under the same key the
        // full entity's row mapper uses.
        assertThat(parts.selectFields.size).isEqualTo(2)
        assertThat(sql).contains("\"game\".\"name\" as \"gameName\"")
        assertThat(sql.contains("status", ignoreCase = true)).isFalse()
        assertThat(parts.joins.any { it.table.name == "game" }).isTrue()
    }

    @Test
    fun `a case-insensitive like renders as ilike with a backslash escape`() {
        // Given
        val query =
            queryFor().copy(filter = Filter(fieldByName("gameName").containsIgnoreCase("50%")))

        // When
        val parts = translator.translate(query, mapping, fields = setOf("id"))
        val sql = DSL.using(SQLDialect.POSTGRES).renderInlined(parts.condition!!)

        // Then - wrapped in the 1:1 group's EXISTS, like any grouped filter.
        assertThat(sql).contains("exists")
        assertThat(sql).contains("""ilike '%50\%%' escape '\'""")
    }
}
