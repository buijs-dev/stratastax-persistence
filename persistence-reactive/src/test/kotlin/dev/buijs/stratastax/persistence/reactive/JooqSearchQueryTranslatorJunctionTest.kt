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
import dev.buijs.stratastax.persistence.reactive.testfixtures.MATCH
import dev.buijs.stratastax.persistence.reactive.testfixtures.MATCH_TAG
import dev.buijs.stratastax.persistence.reactive.testfixtures.TAG
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.idField
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.tagsIdField
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.tagsNameField
import dev.buijs.stratastax.search.core.api.ComparisonExpression
import dev.buijs.stratastax.search.core.api.ComparisonOperator
import dev.buijs.stratastax.search.core.api.Filter
import dev.buijs.stratastax.search.core.api.FilterValue
import dev.buijs.stratastax.search.core.api.SearchQuery
import dev.buijs.stratastax.search.core.spi.FieldGroupRelation
import dev.buijs.stratastax.search.core.spi.StandardSearchConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.jooq.SQLDialect
import org.jooq.conf.ParamType
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test

/**
 * Covers the many-to-many `junction<...> { }` relation - both the `MULTISET` projection
 * ([renderGroups]'s `OneToMany` branch) and filter-side `EXISTS` ([wrapExists]) need to hop root ->
 * junction -> target instead of a single direct join/EXISTS, unlike a plain `join<...> { }` 1:N
 * group (see [JooqSearchQueryTranslatorGroupTest] for that baseline).
 */
internal class JooqSearchQueryTranslatorJunctionTest {

    private val translator = JooqSearchQueryTranslator()

    private val configuration =
        StandardSearchConfiguration(
            defaultSort = listOf(SortField(idField, SortDirection.ASC)),
            fields = listOf(idField, tagsNameField, tagsIdField),
            tiebreakerField = idField,
        )

    private val mapping =
        JooqFieldMapping(
            fields =
                mapOf(
                    "id" to JooqField(field = MATCH.PUBLIC_ID, selectable = true),
                    "tagsName" to JooqField(field = TAG.NAME, selectable = true),
                    "tagsId" to JooqField(field = TAG.PUBLIC_ID, selectable = true),
                ),
            groups =
                listOf(
                    JooqFieldGroup(
                        name = "tags",
                        relation = FieldGroupRelation.OneToMany,
                        from = MATCH.ID,
                        to = TAG.ID,
                        fieldNames = listOf("tagsName", "tagsId"),
                        junctionTable = MATCH_TAG,
                        junctionRootColumn = MATCH_TAG.MATCH_ID,
                        junctionTargetColumn = MATCH_TAG.TAG_ID,
                    ),
                ),
        )

    private fun fieldByName(name: String) = configuration.fields.first { it.name == name }

    @Test
    fun `selectFields multisets the target table through the junction table`() {
        // Given
        val query =
            SearchQuery(
                limit = 20,
                sort = Sort(listOf(SortField(fieldByName("id"), SortDirection.ASC))),
                filter = null,
                cursor = null,
            )

        // When
        val parts = translator.translate(query, mapping)
        val sql =
            DSL
                .using(SQLDialect.POSTGRES)
                .select(parts.selectFields)
                .from(MATCH)
                .applyJoins(parts.joins)
                .getSQL(ParamType.INLINED)

        // Then - the tags subquery joins match_tag to tag, correlated on
        // match_tag.match_id = match.id, not a direct match<->tag relation.
        // no *top-level* join - "tags" is entirely inside its own subquery
        assertThat(parts.joins.size).isEqualTo(0)
        assertThat(sql.contains("match_tag", ignoreCase = true)).isTrue()
        assertThat(sql.contains("\"tag\"", ignoreCase = true)).isTrue()
        assertThat(sql.contains("as \"tags\"")).isTrue()
        assertThat(sql.contains("'tagsName', \"tag\".\"name\"")).isTrue()
        assertThat(sql.contains("'tagsId', \"tag\".\"public_id\"")).isTrue()
    }

    @Test
    fun `a filter on a junction group field renders one EXISTS through the junction table`() {
        // Given - "tags.name = 'ruleset'"
        val expr =
            ComparisonExpression(
                fieldByName("tagsName"),
                ComparisonOperator.EQUALS,
                FilterValue.StringValue("ruleset"),
            )
        val query =
            SearchQuery(
                limit = 20,
                sort = Sort(listOf(SortField(fieldByName("id"), SortDirection.ASC))),
                filter = Filter(expr),
                cursor = null,
            )

        // When
        val parts = translator.translate(query, mapping)
        val sql = parts.condition?.let { DSL.using(SQLDialect.POSTGRES).render(it) }.orEmpty()

        // Then - exactly one EXISTS, routed through match_tag to tag.
        assertThat(Regex("(?i)exists").findAll(sql).count()).isEqualTo(1)
        assertThat(sql.contains("match_tag", ignoreCase = true)).isTrue()
        assertThat(sql.contains("tag", ignoreCase = true)).isTrue()
    }
}
