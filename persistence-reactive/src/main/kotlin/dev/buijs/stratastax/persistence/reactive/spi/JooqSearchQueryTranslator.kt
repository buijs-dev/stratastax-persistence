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
import dev.buijs.stratastax.persistence.reactive.api.PostgresDialect
import dev.buijs.stratastax.persistence.reactive.internal.TranslationPlan
import dev.buijs.stratastax.persistence.reactive.internal.isOneOf
import dev.buijs.stratastax.persistence.reactive.internal.orderBy
import dev.buijs.stratastax.persistence.reactive.internal.seekCondition
import dev.buijs.stratastax.search.core.api.SearchQuery
import dev.buijs.stratastax.search.core.spi.SearchQueryTranslator
import org.jooq.Condition
import org.jooq.impl.DSL
import java.util.Collections
import java.util.WeakHashMap

/**
 * Translates a [SearchQuery] over a [JooqFieldMapping] to [JooqSearchParts]: the caller's filter
 * plus the mapping's default filters, the sort with its keyset (cursor) condition, and the select
 * list with the joins it needs.
 *
 * - A root field is selected under its name; a one-to-one group's field through a join of the
 *   group's table, aliased as the group's name.
 * - A one-to-many or junction group is selected as one JSON array column named after the group,
 *   its elements ordered by the related table's primary key, soft-deleted children left out.
 * - A filter on a group field becomes an `EXISTS` subquery, so it filters the root rows and never
 *   multiplies them.
 *
 * The parts of a mapping that do not depend on the search are built once and reused; mappings are
 * expected to be long-lived (one per entity).
 */
public open class JooqSearchQueryTranslator(
    private val dialect: PersistenceDialect = PostgresDialect,
) : SearchQueryTranslator<JooqFieldMapping, JooqSearchParts> {

    private val plans: MutableMap<JooqFieldMapping, TranslationPlan> =
        Collections.synchronizedMap(
            WeakHashMap(),
        )

    private fun planFor(mapping: JooqFieldMapping): TranslationPlan =
        plans.getOrPut(mapping) { TranslationPlan(mapping, dialect) }

    /** Translates [query] for a full read: every selectable field. */
    override fun translate(
        query: SearchQuery,
        mapping: JooqFieldMapping,
    ): JooqSearchParts {
        val plan = planFor(mapping)
        return JooqSearchParts(
            condition = condition(query, plan),
            orderBy = plan.orderBy(query),
            selectFields = plan.selectFields(),
            joins = plan.joins(),
            seekCondition = plan.seekCondition(query),
        )
    }

    /**
     * Translates [query] for a projection: only the fields named in [fields] are selected, and only
     * the joins those fields and the sort need are kept.
     */
    public fun translate(
        query: SearchQuery,
        mapping: JooqFieldMapping,
        fields: Set<String>,
    ): JooqSearchParts {
        val plan = planFor(mapping)
        return JooqSearchParts(
            condition = condition(query, plan),
            orderBy = plan.orderBy(query),
            selectFields = plan.selectFields(fields),
            joins = plan.joins(fields, query.sort.fields.map { it.field.name }),
            seekCondition = plan.seekCondition(query),
        )
    }

    /** The condition of [query] only - its filter and the default filters - for a count. */
    public fun condition(
        query: SearchQuery,
        mapping: JooqFieldMapping,
    ): Condition? = condition(query, planFor(mapping))

    /** Translates a lookup of the row where [field] equals [value]; the default filters apply. */
    public fun translateById(
        field: EntityField,
        value: Any,
        mapping: JooqFieldMapping,
    ): JooqSearchParts = translateByIds(field, listOf(value), mapping)

    /** Translates a lookup of the rows where [field] is one of [values]; the default filters apply. */
    public fun translateByIds(
        field: EntityField,
        values: Collection<Any>,
        mapping: JooqFieldMapping,
    ): JooqSearchParts {
        val plan = planFor(mapping)
        val idCondition = plan.conditionOn(field.name) { it.isOneOf(values) }

        return JooqSearchParts(
            condition = DSL.and(listOf(idCondition) + plan.defaultConditions),
            orderBy = emptyList(),
            selectFields = plan.selectFields(),
            joins = plan.joins(),
        )
    }

    private fun condition(
        query: SearchQuery,
        plan: TranslationPlan,
    ): Condition? {
        val conditions =
            listOfNotNull(query.filter?.let { plan.filterCondition(it.root) }) +
                plan.defaultConditions
        return if (conditions.isEmpty()) null else DSL.and(conditions)
    }
}
