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
import dev.buijs.stratastax.persistence.reactive.testfixtures.GAME
import dev.buijs.stratastax.persistence.reactive.testfixtures.GHOST_PLAYER
import dev.buijs.stratastax.persistence.reactive.testfixtures.MATCH
import dev.buijs.stratastax.persistence.reactive.testfixtures.MATCH_PARTICIPANT
import dev.buijs.stratastax.persistence.reactive.testfixtures.MatchEntity
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.gameNameField
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.idField
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.participantsGhostPlayerDisplayNameField
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.participantsIdField
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.participantsNameField
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.participantsScoreField
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.statusField
import dev.buijs.stratastax.search.core.api.AndExpression
import dev.buijs.stratastax.search.core.api.ComparisonExpression
import dev.buijs.stratastax.search.core.api.ComparisonOperator
import dev.buijs.stratastax.search.core.api.Filter
import dev.buijs.stratastax.search.core.api.FilterExpression
import dev.buijs.stratastax.search.core.api.FilterValue
import dev.buijs.stratastax.search.core.api.InExpression
import dev.buijs.stratastax.search.core.api.InOperator
import dev.buijs.stratastax.search.core.api.SearchQuery
import dev.buijs.stratastax.search.core.spi.CursorElement
import dev.buijs.stratastax.search.core.spi.FieldGroupRelation
import dev.buijs.stratastax.search.core.spi.StandardSearchConfiguration
import dev.buijs.stratastax.search.core.spi.toCursorElement
import org.assertj.core.api.Assertions.assertThat
import org.jooq.Condition
import org.jooq.SQLDialect
import org.jooq.conf.ParamType
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test

internal class JooqSearchQueryTranslatorGroupTest {

    private val translator = JooqSearchQueryTranslator()

    private fun cursor(fieldName: String): (MatchEntity) -> CursorElement =
        { m ->
            m.id.toCursorElement(fieldName)
        }

    private val configuration =
        StandardSearchConfiguration(
            defaultSort = listOf(SortField(idField, SortDirection.ASC)),
            fields =
                listOf(
                    idField,
                    statusField,
                    gameNameField,
                    participantsNameField,
                    participantsScoreField,
                    participantsGhostPlayerDisplayNameField,
                ),
            tiebreakerField = idField,
        )
    private val cursorValueExtractors =
        mapOf(
            "id" to cursor("id"),
            "status" to cursor("status"),
            "gameName" to cursor("gameName"),
            "participantsName" to cursor("participantsName"),
            "participantsScore" to cursor("participantsScore"),
            "participantsGhostPlayerDisplayName" to cursor("participantsGhostPlayerDisplayName"),
        )
    private val mapping =
        JooqFieldMapping(
            fields =
                mapOf(
                    "id" to JooqField(field = MATCH.PUBLIC_ID, selectable = true),
                    "status" to JooqField(field = MATCH.STATUS, selectable = true),
                    "gameName" to JooqField(field = GAME.NAME, selectable = true),
                    "participantsName" to
                        JooqField(field = MATCH_PARTICIPANT.NAME, selectable = true),
                    "participantsScore" to
                        JooqField(field = MATCH_PARTICIPANT.SCORE, selectable = true),
                    "participantsGhostPlayerDisplayName" to
                        JooqField(field = GHOST_PLAYER.DISPLAY_NAME, selectable = true),
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
                    JooqFieldGroup(
                        name = "participants",
                        relation = FieldGroupRelation.OneToMany,
                        from = MATCH.ID,
                        to = MATCH_PARTICIPANT.ID,
                        fieldNames = listOf("participantsName", "participantsScore"),
                    ),
                    JooqFieldGroup(
                        name = "participantsGhostPlayer",
                        relation = FieldGroupRelation.OneToOne,
                        from = MATCH_PARTICIPANT.GHOST_PLAYER_ID,
                        to = GHOST_PLAYER.ID,
                        parentName = "participants",
                        fieldNames = listOf("participantsGhostPlayerDisplayName"),
                    ),
                ),
        )

    private fun fieldByName(name: String) = configuration.fields.first { it.name == name }

    private fun render(condition: Condition?): String =
        condition?.let { DSL.using(SQLDialect.POSTGRES).render(it) } ?: ""

    private fun existsCount(sql: String): Int = Regex("(?i)exists").findAll(sql).count()

    private fun queryFor(vararg filters: FilterExpression): SearchQuery {
        val root = if (filters.size == 1) filters[0] else AndExpression(filters.toList())
        return SearchQuery(
            limit = 20,
            sort = Sort(listOf(SortField(fieldByName("id"), SortDirection.ASC))),
            filter = Filter(root),
            cursor = null,
        )
    }

    @Test
    fun `root field filter renders as a plain predicate without EXISTS`() {
        // Given
        val expr =
            ComparisonExpression(
                fieldByName("status"),
                ComparisonOperator.EQUALS,
                FilterValue.StringValue("ACTIVE"),
            )

        // When
        val parts = translator.translate(queryFor(expr), mapping)
        val sql = render(parts.condition)

        // Then
        assertThat(sql.contains("status")).isTrue()
        assertThat(existsCount(sql)).isEqualTo(0)
    }

    @Test
    fun `1 to 1 group field filter renders as one EXISTS`() {
        // Given
        val expr =
            ComparisonExpression(
                fieldByName("gameName"),
                ComparisonOperator.EQUALS,
                FilterValue.StringValue("foo"),
            )

        // When
        val parts = translator.translate(queryFor(expr), mapping)
        val sql = render(parts.condition)

        // Then
        assertThat(existsCount(sql)).isEqualTo(1)
        assertThat(sql.contains("game")).isTrue()
    }

    @Test
    fun `1 to many group field filter renders as one EXISTS`() {
        // Given - "participant.name is in (piet, bro)"
        val expr =
            InExpression(
                fieldByName("participantsName"),
                InOperator.IN,
                listOf(FilterValue.StringValue("piet"), FilterValue.StringValue("bro")),
            )

        // When
        val parts = translator.translate(queryFor(expr), mapping)
        val sql = render(parts.condition)

        // Then
        assertThat(existsCount(sql)).isEqualTo(1)
        assertThat(sql.contains("match_participant")).isTrue()
    }

    @Test
    fun `nested group field filter renders as two nested EXISTS`() {
        // Given
        val expr =
            ComparisonExpression(
                fieldByName("participantsGhostPlayerDisplayName"),
                ComparisonOperator.EQUALS,
                FilterValue.StringValue("foo"),
            )

        // When
        val parts = translator.translate(queryFor(expr), mapping)
        val sql = render(parts.condition)

        // Then
        assertThat(existsCount(sql)).isEqualTo(2)
        assertThat(sql.contains("match_participant")).isTrue()
        assertThat(sql.contains("ghost_player")).isTrue()
    }

    @Test
    fun `selectFields joins the 1 to 1 group and multisets the 1 to many group`() {
        // Given
        val expr =
            ComparisonExpression(
                fieldByName("status"),
                ComparisonOperator.EQUALS,
                FilterValue.StringValue("ACTIVE"),
            )

        // When
        val parts = translator.translate(queryFor(expr), mapping)
        val sql =
            DSL
                .using(SQLDialect.POSTGRES)
                .select(parts.selectFields)
                .from(MATCH)
                .applyJoins(parts.joins)
                .getSQL(ParamType.INLINED)

        // Then - one top-level JOIN (the 1:1 "game"; "ghostPlayer" also joins,
        // but nested *inside* the "participants" `MULTISET` subquery, not at
        // the top level). Postgres has no native MULTISET syntax, so jOOQ
        // emulates it as a single aggregated subquery field aliased
        // "participants" - assert on that shape rather than a literal
        // "multiset" keyword.
        assertThat(parts.joins.size).isEqualTo(1)
        assertThat(Regex("(?i)\\bjoin\\b").findAll(sql).count()).isEqualTo(2)
        assertThat(sql.contains("as \"participants\"")).isTrue()
        assertThat(sql.contains("game", ignoreCase = true)).isTrue()
        assertThat(sql.contains("match_participant", ignoreCase = true)).isTrue()
        assertThat(sql.contains("ghost_player", ignoreCase = true)).isTrue()

        // "ghost_player" is bounded inside the "participants" subquery - i.e.
        // it's joined there, not at the top level.
        val subqueryStart = sql.indexOf("coalesce(")
        val subqueryEnd = sql.indexOf("as \"participants\"")
        val ghostPlayerIndex = sql.indexOf("ghost_player", ignoreCase = true)
        assertThat(ghostPlayerIndex in subqueryStart..subqueryEnd).isTrue()
    }

    @Test
    fun `1 to many group elements are encoded as named JSON objects, not positional arrays`() {
        // Given
        val expr =
            ComparisonExpression(
                fieldByName("status"),
                ComparisonOperator.EQUALS,
                FilterValue.StringValue("ACTIVE"),
            )

        // When
        val parts = translator.translate(queryFor(expr), mapping)
        val sql =
            DSL
                .using(SQLDialect.POSTGRES)
                .select(parts.selectFields)
                .from(MATCH)
                .applyJoins(parts.joins)
                .getSQL(ParamType.INLINED)

        // Then - a Vert.x `Row`/`JsonArray` reading the wire format directly
        // (no jOOQ field-identity metadata) needs each nested element keyed
        // by name, not position - so the aggregate must be built from
        // jsonb_build_object(key, value, ...), not jOOQ's own positional
        // MULTISET emulation (jsonb_build_array(...)). Keys are the same
        // fully-qualified names the generated XxxSearch class exposes (e.g.
        // "participantsName", not "name") - the same class is meant to
        // reliably key into any row, root or nested. Each *value* must be
        // the real, fully-qualified column reference, not a bare reference
        // to its own alias (see the dedicated regression test below).
        assertThat(sql.contains("jsonb_build_object")).isTrue()
        assertThat(sql.contains("'participantsName', \"match_participant\".\"name\"")).isTrue()
        assertThat(sql.contains("'participantsScore', \"match_participant\".\"score\"")).isTrue()
        assertThat(
            sql.contains(
                "'participantsGhostPlayerDisplayName', \"ghost_player\".\"display_name\"",
            ),
        ).isTrue()
        // Empty-child-rows guard: preserved from jOOQ's own multiset
        // emulation (see the previous test) - a plain jsonb_agg over zero
        // rows returns NULL, not [], so this must stay wrapped in coalesce.
        assertThat(sql.contains("coalesce(")).isTrue()
        assertThat(sql.contains("jsonb_build_array()")).isTrue()
    }

    @Test
    fun `jsonb_build_object keys are inlined literals, never bind parameters`() {
        // Given - reproduces a real reported bug: executed via the reactive
        // Vert.x Postgres driver (extended query protocol), a jsonEntry key
        // rendered as an ordinary bind parameter fails at prepare time with
        // "could not determine data type of parameter $1" - jsonb_build_object
        // accepts polymorphic "any" arguments, so Postgres can't infer a type
        // for a parameter placeholder there on its own. The key is a literal
        // known at render time (never user input) and must be inlined
        // directly into the SQL text instead - this test renders with
        // ParamType.INDEXED (what the DSLContext actually sends for
        // execution, unlike ParamType.INLINED which the other tests in this
        // file use and which would mask this bug by inlining everything).
        val expr =
            ComparisonExpression(
                fieldByName("status"),
                ComparisonOperator.EQUALS,
                FilterValue.StringValue("ACTIVE"),
            )

        // When
        val parts = translator.translate(queryFor(expr), mapping)
        val sql =
            DSL
                .using(SQLDialect.POSTGRES)
                .select(parts.selectFields)
                .from(MATCH)
                .applyJoins(parts.joins)
                .where(parts.condition)
                .getSQL(ParamType.INDEXED)

        // Then - the jsonEntry keys are literal strings, not `?` placeholders
        // (jOOQ's own ParamType.INDEXED rendering uses JDBC-style `?` - the
        // real Vert.x driver then substitutes dialect-specific `$1`, `$2`, ...
        // for each one at bind time, which is exactly where the reported
        // failure happened)...
        assertThat(sql.contains("'participantsName'")).isTrue()
        assertThat(sql.contains("'participantsScore'")).isTrue()
        assertThat(sql.contains("'participantsGhostPlayerDisplayName'")).isTrue()
        // ...while the genuinely dynamic WHERE-clause filter value is still a
        // real bind parameter - proving this test would actually catch a
        // regression back to binding the keys too (a fully-inlined query
        // would contain zero `?` placeholders anywhere).
        assertThat(sql.contains("?")).isTrue()
    }

    @Test
    fun `1 to many group element values reference the real column, not a bare alias identifier`() {
        val localConfiguration =
            StandardSearchConfiguration(
                defaultSort = listOf(SortField(idField, SortDirection.ASC)),
                fields = listOf(idField, participantsIdField),
                tiebreakerField = idField,
            )
        val localMapping =
            JooqFieldMapping(
                fields =
                    mapOf(
                        "id" to JooqField(field = MATCH.PUBLIC_ID, selectable = true),
                        "participantsId" to
                            JooqField(field = MATCH_PARTICIPANT.PUBLIC_ID, selectable = true),
                    ),
                groups =
                    listOf(
                        JooqFieldGroup(
                            name = "participants",
                            relation = FieldGroupRelation.OneToMany,
                            from = MATCH.ID,
                            to = MATCH_PARTICIPANT.ID,
                            fieldNames = listOf("participantsId"),
                        ),
                    ),
            )

        val query =
            SearchQuery(
                limit = 20,
                sort =
                    Sort(
                        listOf(
                            SortField(
                                localConfiguration.fields.first { it.name == "id" },
                                SortDirection.ASC,
                            ),
                        ),
                    ),
                filter = null,
                cursor = null,
            )

        // When
        val parts = translator.translate(query, localMapping)
        val sql =
            DSL
                .using(SQLDialect.POSTGRES)
                .select(parts.selectFields)
                .from(MATCH)
                .applyJoins(parts.joins)
                .getSQL(ParamType.INLINED)

        // Then - qualified key ("participantsId", matching the generated
        // XxxSearch property playsetSearch.participantsId), real column
        // reference as the value, not a bare reference to its own alias.
        assertThat(sql.contains("'participantsId', \"match_participant\".\"public_id\"")).isTrue()
        assertThat(sql.contains("'participantsId', \"participantsId\"")).isFalse()
    }

    @Test
    fun `sorting by a 1 to 1 group field renders an ORDER BY against its joined column`() {
        // Given - "game" is a 1:1 group and "game.name" is sortable = true
        val query =
            SearchQuery(
                limit = 20,
                sort = Sort(listOf(SortField(fieldByName("gameName"), SortDirection.ASC))),
                filter = null,
                cursor = null,
            )

        // When
        val parts = translator.translate(query, mapping)

        // Then - the ORDER BY resolves the "game.name" column directly; the
        // top-level JOIN to "game" (always present in `joins`, since
        // selectFields/joins are rendered independently of what's sorted) is
        // what makes this valid SQL.
        assertThat(parts.orderBy.size).isEqualTo(1)
        val orderSql = DSL.using(SQLDialect.POSTGRES).render(parts.orderBy[0])
        assertThat(orderSql.contains("name", ignoreCase = true)).isTrue()
        // Every non-junction 1:1 group is now always aliased under its own
        // name, so the joined table is GAME.as("game"), not GAME itself -
        // compare by alias name instead of table identity.
        assertThat(parts.joins.any { it.table.name == "game" }).isTrue()
    }
}
