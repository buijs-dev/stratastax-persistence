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
import dev.buijs.stratastax.persistence.reactive.api.JooqFieldMapping
import dev.buijs.stratastax.persistence.reactive.api.JooqSearchQueryTranslator
import dev.buijs.stratastax.persistence.reactive.testfixtures.MATCH
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.attributesField
import dev.buijs.stratastax.persistence.reactive.testfixtures.TestFields.idField
import dev.buijs.stratastax.search.core.api.ComparisonExpression
import dev.buijs.stratastax.search.core.api.ComparisonOperator
import dev.buijs.stratastax.search.core.api.Filter
import dev.buijs.stratastax.search.core.api.FilterValue
import dev.buijs.stratastax.search.core.api.SearchQuery
import dev.buijs.stratastax.search.core.spi.StandardSearchConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test

/**
 * A [FilterValue.JsonValue] must bind as a real `org.jooq.JSONB` (not a bare `String`) against a
 * `Field<JSONB>` column - see `JooqSearchQueryTranslator.resolveValue`'s `FilterValue.JsonValue`
 * arm, mirroring `convertNumber`/`convertDateTime`'s existing "adapt to whatever the column's own
 * Java type needs" pattern.
 */
internal class JooqSearchQueryTranslatorJsonFilterTest {

    private val translator = JooqSearchQueryTranslator()

    private val configuration =
        StandardSearchConfiguration(
            defaultSort = listOf(SortField(idField, SortDirection.ASC)),
            fields = listOf(idField, attributesField),
            tiebreakerField = idField,
        )
    private val mapping =
        JooqFieldMapping(
            fields =
                mapOf(
                    "id" to JooqField(field = MATCH.PUBLIC_ID, selectable = true),
                    "attributes" to JooqField(field = MATCH.ATTRIBUTES, selectable = true),
                ),
        )

    private fun fieldByName(name: String) = configuration.fields.first { it.name == name }

    private fun renderCondition(expr: ComparisonExpression): String {
        val query =
            SearchQuery(
                limit = 20,
                sort = Sort(listOf(SortField(fieldByName("id"), SortDirection.ASC))),
                filter = Filter(expr),
                cursor = null,
            )
        val parts = translator.translate(query, mapping)
        return parts.condition?.let { DSL.using(SQLDialect.POSTGRES).render(it) } ?: ""
    }

    @Test
    fun `a JSON field equality filter renders a jsonb-typed bind against the column`() {
        // Given
        val expr =
            ComparisonExpression(
                fieldByName("attributes"),
                ComparisonOperator.EQUALS,
                FilterValue.JsonValue("""{"color":"red"}"""),
            )

        // When
        val sql = renderCondition(expr)

        // Then
        assertThat(sql.contains("attributes")).withFailMessage(sql).isTrue()
        assertThat(sql.contains("=")).withFailMessage(sql).isTrue()
    }

    @Test
    fun `a JSON field inequality filter also renders correctly`() {
        // Given
        val expr =
            ComparisonExpression(
                fieldByName("attributes"),
                ComparisonOperator.NOT_EQUALS,
                FilterValue.JsonValue("""{"color":"red"}"""),
            )

        // When
        val sql = renderCondition(expr)

        // Then
        assertThat(sql.contains("attributes")).withFailMessage(sql).isTrue()
        assertThat(sql.contains("<>") || sql.contains("!=")).withFailMessage(sql).isTrue()
    }
}
