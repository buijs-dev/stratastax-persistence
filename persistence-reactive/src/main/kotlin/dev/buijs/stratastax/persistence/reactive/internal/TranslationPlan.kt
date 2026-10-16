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
package dev.buijs.stratastax.persistence.reactive.internal

import dev.buijs.stratastax.entity.core.api.SortDirection
import dev.buijs.stratastax.persistence.reactive.spi.JooqFieldGroup
import dev.buijs.stratastax.persistence.reactive.spi.JooqFieldMapping
import dev.buijs.stratastax.persistence.reactive.spi.JooqJoin
import dev.buijs.stratastax.persistence.reactive.spi.JunctionGroup
import dev.buijs.stratastax.persistence.reactive.spi.OneToManyGroup
import dev.buijs.stratastax.persistence.reactive.spi.OneToOneGroup
import dev.buijs.stratastax.persistence.reactive.spi.PersistenceDialect
import dev.buijs.stratastax.persistence.reactive.spi.applyJoin
import dev.buijs.stratastax.search.core.api.AndExpression
import dev.buijs.stratastax.search.core.api.ComparisonExpression
import dev.buijs.stratastax.search.core.api.ComparisonOperator
import dev.buijs.stratastax.search.core.api.CursorDirection
import dev.buijs.stratastax.search.core.api.FilterExpression
import dev.buijs.stratastax.search.core.api.FilterValue
import dev.buijs.stratastax.search.core.api.InExpression
import dev.buijs.stratastax.search.core.api.InOperator
import dev.buijs.stratastax.search.core.api.LikeExpression
import dev.buijs.stratastax.search.core.api.OrExpression
import dev.buijs.stratastax.search.core.api.SearchException
import dev.buijs.stratastax.search.core.api.SearchQuery
import org.jooq.Condition
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.JSONEntry
import org.jooq.OrderField
import org.jooq.SelectFieldOrAsterisk
import org.jooq.SortField
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * Resolves the columns of one table occurrence in a query: the root table as is, or a related
 * table under an alias. Every related table is aliased - in joins, `EXISTS` and array subqueries -
 * so a relation to the same table as its parent (`category.parent`) never shadows the parent.
 */
private class Scope(
    private val table: Table<*>?,
) {

    fun column(raw: Field<*>): Field<Any?> {
        val aliased = table ?: return raw.untyped()
        return aliased.field(raw)?.untyped()
            ?: throw IllegalArgumentException(
                "Column '${raw.name}' does not belong to table '${aliased.name}'",
            )
    }

    companion object {

        /** The unaliased root table. */
        val ROOT = Scope(null)
    }
}

/** A select-list item made from a group, and the one-to-one groups whose joins it needs. */
private class GroupSelect(
    val alias: String,
    val expression: Field<*>,
    val requiredJoins: List<String>,
)

/** A join of a one-to-one group, tagged with that group for pruning. */
private class GroupJoin(
    val groupName: String,
    val join: JooqJoin,
)

/**
 * Everything about translating searches over one [JooqFieldMapping] that does not depend on the
 * search itself: the select list, the joins and the default filters. Built once per mapping.
 */
internal class TranslationPlan(
    private val mapping: JooqFieldMapping,
    private val dialect: PersistenceDialect,
) {

    private val tree = GroupTree(mapping.groups)

    /**
     * The tables the root fields live in - the tables a query references without an alias. A group
     * alias must not shadow them (`category.parent` on `category`).
     */
    private val rootTableNames: Set<String> =
        mapping.fields
            .filterKeys { tree.groupFor(it) == null }
            .values
            .mapNotNull { (it.field as? TableField<*, *>)?.table?.name?.lowercase() }
            .toSet()

    private val rootSelects: List<Pair<String, Field<*>>> =
        mapping.fields.entries
            .filter { (name, field) -> field.selectable && tree.groupFor(name) == null }
            .map { (name, field) -> name to field.field }

    private val groupSelects = mutableListOf<GroupSelect>()

    private val groupJoins = mutableListOf<GroupJoin>()

    /** The default filters (`fixedValue` fields), applied to every read. */
    val defaultConditions: List<Condition>

    init {
        renderTopLevel(
            parentName = null,
            parentScope = Scope.ROOT,
            optionalParent = false,
            requiredJoins = emptyList(),
        )
        defaultConditions =
            mapping.fields.entries.mapNotNull { (name, field) ->
                field.defaultFilterValue?.let { fixed ->
                    val value = filterValueFor(fixed, field.field.type)
                    conditionOn(name, matchesNull = value == null) { column ->
                        if (value == null) {
                            column.isNull
                        } else {
                            column.eq(value)
                        }
                    }
                }
            }
    }

    /** The select list: every selectable field, or only the ones in [only]. */
    fun selectFields(only: Set<String>? = null): List<SelectFieldOrAsterisk> {
        val roots =
            rootSelects.filter { only == null || it.first in only }.map { (name, field) ->
                field.`as`(name)
            }

        val groups =
            groupSelects
                .filter { only == null || it.alias in only }
                .map { it.expression.`as`(it.alias) }

        return roots + groups
    }

    /**
     * The joins of the one-to-one groups: all of them, or with [only] just the ones the selected
     * fields and the sort on [sortFields] need.
     */
    fun joins(
        only: Set<String>? = null,
        sortFields: List<String> = emptyList(),
    ): List<JooqJoin> {
        if (only == null) return groupJoins.map { it.join }

        val required =
            groupSelects.filter { it.alias in only }.flatMap { it.requiredJoins }.toSet() +
                sortFields.flatMap(::joinsForSort)
        return groupJoins.filter { it.groupName in required }.map { it.join }
    }

    /** The caller's filter as a condition; a filter on a group field becomes an `EXISTS`. */
    fun filterCondition(expression: FilterExpression): Condition =
        when (expression) {
            is AndExpression -> {
                DSL.and(expression.children.map(::filterCondition))
            }
            is OrExpression -> {
                DSL.or(expression.children.map(::filterCondition))
            }
            is ComparisonExpression -> {
                val matchesNull =
                    expression.operator == ComparisonOperator.EQUALS &&
                        expression.value is FilterValue.NullValue
                conditionOn(expression.field.name, matchesNull) { comparison(it, expression) }
            }
            is InExpression -> {
                val matchesNull =
                    expression.operator == InOperator.IN &&
                        expression.values.any { it is FilterValue.NullValue }

                conditionOn(expression.field.name, matchesNull) { membership(it, expression) }
            }
            is LikeExpression -> {
                conditionOn(expression.field.name) { like(it, expression) }
            }
        }

    /**
     * [build]'s condition on field [fieldName]'s column - wrapped in `EXISTS` subqueries along the
     * field's group chain when it belongs to a group.
     *
     * A field of a one-to-one relation reads as `NULL` when the relation is absent, as it does in
     * the read projection: a condition that [matchesNull] (`IS NULL`, `IN (..., NULL)`) also matches
     * a row without the related row.
     */
    fun conditionOn(
        fieldName: String,
        matchesNull: Boolean = false,
        build: (Field<Any?>) -> Condition,
    ): Condition {
        val raw = mapping.field(fieldName).field
        val group = tree.groupFor(fieldName) ?: return build(raw.untyped())
        val chain = tree.chainTo(group)
        val matching =
            existsAlong(
                chain,
                index = 0,
                parentScope = Scope.ROOT,
            ) { scope -> build(scope.column(raw)) }

        if (!matchesNull || chain.any { it !is OneToOneGroup }) return matching
        return matching.or(
            DSL.not(
                existsAlong(chain, index = 0, parentScope = Scope.ROOT) { DSL.trueCondition() },
            ),
        )
    }

    /**
     * The column to sort on for field [name]: the root column, or a joined one-to-one group's
     * aliased column.
     *
     * @throws SearchException for a field of a one-to-many relation, which has no single value.
     */
    fun sortColumn(name: String): Field<Any?> {
        val raw = mapping.field(name).field
        val group = tree.groupFor(name) ?: return raw.untyped()
        if (tree.chainTo(group).any { it !is OneToOneGroup }) {
            throw SearchException(
                "Field '$name' belongs to a one-to-many relation and cannot be sorted on",
            )
        }

        return Scope(topLevelTable(group as OneToOneGroup)).column(raw)
    }

    /**
     * Whether field [name] can never read as NULL: a NOT NULL column of the root table. A column
     * of a related table reads as NULL when an optional relation is absent.
     */
    fun isNeverNull(name: String): Boolean =
        tree.groupFor(name) == null &&
            !mapping
                .field(name)
                .field.dataType
                .nullable()

    private fun joinsForSort(name: String): List<String> {
        val group = tree.groupFor(name) ?: return emptyList()
        return tree.chainTo(group).map { it.name }
    }

    // Top level: one-to-one groups are joined, one-to-many groups become JSON array columns.

    private fun renderTopLevel(
        parentName: String?,
        parentScope: Scope,
        optionalParent: Boolean,
        requiredJoins: List<String>,
    ) {
        for (group in tree.childrenOf(parentName)) {
            when (group) {
                is OneToOneGroup -> {
                    val table = topLevelTable(group)
                    val scope = Scope(table)
                    val left = optionalParent || group.from.dataType.nullable()
                    val condition = parentScope.column(group.from).joinedOn(scope.column(group.to))
                    groupJoins += GroupJoin(group.name, JooqJoin(table, condition, left))
                    val chain = requiredJoins + group.name
                    ownFields(group).forEach { (name, field) ->
                        groupSelects +=
                            GroupSelect(name, scope.column(field), chain)
                    }

                    renderTopLevel(group.name, scope, left, chain)
                }
                is OneToManyGroup, is JunctionGroup -> {
                    groupSelects +=
                        GroupSelect(group.name, jsonArrayOf(group, parentScope), requiredJoins)
                }
            }
        }
    }

    private fun topLevelTable(group: OneToOneGroup): Table<*> {
        val name = group.name
        val clashes = dialect.isReserved(name) || name.lowercase() in rootTableNames
        return group.to.owningTable().`as`(if (clashes) "${name}_" else name)
    }

    private fun ownFields(group: JooqFieldGroup): List<Pair<String, Field<*>>> =
        mapping.fields.entries
            .filter { (name, field) -> field.selectable && tree.groupFor(name)?.name == group.name }
            .map { (name, field) -> name to field.field }

    // One-to-many groups: a correlated subquery aggregating the rows into a JSON array.

    private fun jsonArrayOf(
        group: JooqFieldGroup,
        parentScope: Scope,
    ): Field<*> {
        val source = arraySource(group, parentScope)
        val entries = mutableListOf<JSONEntry<*>>()
        val joins = mutableListOf<JooqJoin>()
        elementContent(group, source.scope, optional = false, entries, joins)
        val ordered = source.orderBy.ifEmpty { null }

        val element = jsonObjectOf(entries)
        val aggregate: Field<JSONB> =
            if (ordered == null) {
                DSL.jsonbArrayAgg(element)
            } else {
                DSL.jsonbArrayAgg(element).orderBy(ordered)
            }

        val select =
            joins.fold(
                DSL.select(aggregate).from(source.table),
            ) { step, join -> step.applyJoin(join) }

        val rows = select.where(listOfNotNull(source.correlation, source.softDeleteFilter))
        return DSL.coalesce(DSL.field(rows), DSL.jsonbArray())
    }

    /**
     * `jsonb_build_object` takes at most 100 arguments (50 entries); a larger object is built in
     * parts and concatenated.
     */
    private fun jsonObjectOf(entries: List<JSONEntry<*>>): Field<JSONB> =
        entries
            .chunked(JSON_OBJECT_MAX_ENTRIES)
            .map { DSL.jsonbObject(it) as Field<JSONB> }
            .ifEmpty { listOf(DSL.jsonbObject() as Field<JSONB>) }
            .reduce { left, right -> DSL.field("({0} || {1})", SQLDataType.JSONB, left, right) }

    private fun elementContent(
        group: JooqFieldGroup,
        scope: Scope,
        optional: Boolean,
        entries: MutableList<JSONEntry<*>>,
        joins: MutableList<JooqJoin>,
    ) {
        ownFields(group).forEach { (name, field) ->
            entries +=
                DSL.jsonEntry(DSL.inline(name), scope.column(field))
        }

        for (child in tree.childrenOf(group.name)) {
            when (child) {
                is OneToOneGroup -> {
                    val table = child.to.owningTable().`as`("${child.name}_m")
                    val childScope = Scope(table)
                    val left = optional || child.from.dataType.nullable()
                    joins +=
                        JooqJoin(
                            table,
                            scope.column(child.from).joinedOn(childScope.column(child.to)),
                            left,
                        )
                    elementContent(child, childScope, left, entries, joins)
                }
                is OneToManyGroup, is JunctionGroup -> {
                    entries += DSL.jsonEntry(DSL.inline(child.name), jsonArrayOf(child, scope))
                }
            }
        }
    }

    private class ArraySource(
        val table: Table<*>,
        val scope: Scope,
        val correlation: Condition,
        val softDeleteFilter: Condition?,
        val orderBy: List<OrderField<*>>,
    )

    private fun arraySource(
        group: JooqFieldGroup,
        parentScope: Scope,
    ): ArraySource =
        when (group) {
            is OneToManyGroup -> {
                val table = group.to.owningTable().`as`("${group.name}_m")
                val scope = Scope(table)
                ArraySource(
                    table = table,
                    scope = scope,
                    correlation = parentScope.column(group.from).joinedOn(scope.column(group.to)),
                    softDeleteFilter = group.softDeleteColumn?.let { scope.column(it).isNull },
                    orderBy =
                        elementOrder(
                            group.to.owningTable(),
                            group.childIdentityColumn,
                            scope,
                        ),
                )
            }
            is JunctionGroup -> {
                val junction = group.junctionTable.`as`("${group.name}_mj")
                val junctionScope = Scope(junction)
                val target = group.to.owningTable().`as`("${group.name}_m")
                val scope = Scope(target)
                ArraySource(
                    table =
                        junction
                            .join(
                                target,
                            ).on(
                                junctionScope
                                    .column(
                                        group.junctionTargetColumn,
                                    ).joinedOn(scope.column(group.to)),
                            ),
                    scope = scope,
                    correlation =
                        parentScope
                            .column(
                                group.from,
                            ).joinedOn(junctionScope.column(group.junctionRootColumn)),
                    softDeleteFilter = null,
                    orderBy =
                        elementOrder(
                            group.to.owningTable(),
                            group.targetIdentityColumn,
                            scope,
                        ),
                )
            }
            is OneToOneGroup -> {
                throw IllegalArgumentException(
                    "Group '${group.name}' is one-to-one and has no array",
                )
            }
        }

    /** A stable element order: the primary key, else the identity column, else none. */
    private fun elementOrder(
        table: Table<*>,
        identityColumn: TableField<*, *>?,
        scope: Scope,
    ): List<OrderField<*>> {
        val keyColumns =
            table.primaryKey?.fields?.takeIf { it.isNotEmpty() } ?: listOfNotNull(identityColumn)
        return keyColumns.map { scope.column(it) }
    }

    // Filters on group fields: EXISTS along the group chain, each related table aliased.

    private fun existsAlong(
        chain: List<JooqFieldGroup>,
        index: Int,
        parentScope: Scope,
        innermost: (Scope) -> Condition,
    ): Condition {
        val group = chain[index]
        val source = existsSource(group, parentScope)
        val inner =
            if (index == chain.lastIndex) {
                innermost(source.scope)
            } else {
                existsAlong(chain, index + 1, source.scope, innermost)
            }

        val conditions = listOfNotNull(source.correlation, source.softDeleteFilter, inner)
        return DSL.exists(DSL.selectOne().from(source.table).where(conditions))
    }

    private fun existsSource(
        group: JooqFieldGroup,
        parentScope: Scope,
    ): ArraySource =
        when (group) {
            is OneToOneGroup -> {
                val table = group.to.owningTable().`as`("${group.name}_x")
                val scope = Scope(table)
                ArraySource(
                    table,
                    scope,
                    parentScope.column(group.from).joinedOn(scope.column(group.to)),
                    null,
                    emptyList(),
                )
            }
            is OneToManyGroup -> {
                val table = group.to.owningTable().`as`("${group.name}_x")
                val scope = Scope(table)
                ArraySource(
                    table,
                    scope,
                    parentScope.column(group.from).joinedOn(scope.column(group.to)),
                    group.softDeleteColumn?.let { scope.column(it).isNull },
                    emptyList(),
                )
            }
            is JunctionGroup -> {
                val junction = group.junctionTable.`as`("${group.name}_xj")
                val junctionScope = Scope(junction)
                val target = group.to.owningTable().`as`("${group.name}_x")
                val scope = Scope(target)
                ArraySource(
                    junction
                        .join(
                            target,
                        ).on(
                            junctionScope
                                .column(
                                    group.junctionTargetColumn,
                                ).joinedOn(scope.column(group.to)),
                        ),
                    scope,
                    parentScope
                        .column(
                            group.from,
                        ).joinedOn(junctionScope.column(group.junctionRootColumn)),
                    null,
                    emptyList(),
                )
            }
        }

    // Filter operators.

    @Suppress("UNCHECKED_CAST")
    private fun comparison(
        column: Field<Any?>,
        expression: ComparisonExpression,
    ): Condition {
        val value = filterValueFor(expression.value, column.type)
        val comparable = column as Field<Comparable<Any>>
        return when (expression.operator) {
            ComparisonOperator.EQUALS -> {
                if (value == null) column.isNull else column.eq(value)
            }
            ComparisonOperator.NOT_EQUALS -> {
                if (value == null) {
                    column.isNotNull
                } else {
                    column.ne(value)
                }
            }
            ComparisonOperator.GREATER_THAN -> {
                comparable.gt(nonNull(value, expression))
            }
            ComparisonOperator.GREATER_THAN_OR_EQUAL -> {
                comparable.ge(nonNull(value, expression))
            }
            ComparisonOperator.LESS_THAN -> {
                comparable.lt(nonNull(value, expression))
            }
            ComparisonOperator.LESS_THAN_OR_EQUAL -> {
                comparable.le(nonNull(value, expression))
            }
            ComparisonOperator.LIKE, ComparisonOperator.ILIKE -> {
                throw SearchException(
                    "${expression.operator} on '${expression.field.name}' must be a LikeExpression",
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun nonNull(
        value: Any?,
        expression: ComparisonExpression,
    ): Comparable<Any> =
        value as? Comparable<Any>
            ?: throw SearchException(
                "Operator '${expression.operator}' on field '${expression.field.name}' does not accept null",
            )

    private fun membership(
        column: Field<Any?>,
        expression: InExpression,
    ): Condition {
        val values = expression.values.map { filterValueFor(it, column.type) }

        return when (expression.operator) {
            InOperator.IN -> column.isOneOf(values)
            InOperator.OUT -> column.isNoneOf(values)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun like(
        column: Field<Any?>,
        expression: LikeExpression,
    ): Condition {
        val text = column as Field<String>
        return if (expression.ignoreCase) {
            text.likeIgnoreCase(
                expression.pattern,
                '\\',
            )
        } else {
            text.like(expression.pattern, '\\')
        }
    }

    private companion object {

        const val JSON_OBJECT_MAX_ENTRIES = 50
    }
}

/**
 * The sort of [query] on this plan's columns, NULL sorting as the largest value (`ASC NULLS LAST`,
 * `DESC NULLS FIRST`). A page before a cursor reads the order backwards.
 */
internal fun TranslationPlan.orderBy(query: SearchQuery): List<SortField<*>> {
    val backwards = query.cursor?.direction == CursorDirection.BEFORE
    return query.sort.fields.map {
        val column = sortColumn(it.field.name)
        when (it.direction.readAs(backwards)) {
            SortDirection.ASC -> column.asc().nullsLast()
            SortDirection.DESC -> column.desc().nullsFirst()
        }
    }
}

/**
 * The rows past [query]'s cursor in the order [orderBy] reads, or `null` without a cursor: past the
 * cursor's value of the first sort field, or equal to it and past the value of the second, and so
 * on. When every sort column is NOT NULL and sorted the same way, this is a single row-value
 * comparison an index can serve directly.
 *
 * @throws SearchException when the cursor does not belong to the query's sort.
 */
internal fun TranslationPlan.seekCondition(query: SearchQuery): Condition? {
    val cursor = query.cursor ?: return null
    val fields = query.sort.fields
    if (cursor.values.size != fields.size) {
        throw SearchException("Cursor does not match the current sort criteria")
    }

    val backwards = cursor.direction == CursorDirection.BEFORE
    val keys =
        fields.zip(cursor.values).map { (sortField, element) ->
            val column = sortColumn(sortField.field.name)
            SeekKey(
                column = column,
                direction = sortField.direction.readAs(backwards),
                value = cursorValueFor(element, column, sortField.field.name),
                neverNull = isNeverNull(sortField.field.name),
            )
        }

    return rowValueSeek(keys)
        ?: DSL.or(
            keys.indices.map { index ->
                DSL.and(keys.take(index).map { it.equal() } + keys[index].beyond())
            },
        )
}

private fun rowValueSeek(keys: List<SeekKey>): Condition? {
    val direction = keys.first().direction
    val eligible = keys.all { it.direction == direction && it.value != null && it.neverNull }

    if (!eligible || keys.size < 2) return null
    val columns = DSL.row(keys.map { it.column })
    val values = DSL.row(keys.map { DSL.`val`(it.value, it.column) })
    return if (direction == SortDirection.ASC) columns.gt(values) else columns.lt(values)
}

private class SeekKey(
    val column: Field<Any?>,
    val direction: SortDirection,
    val value: Any?,
    val neverNull: Boolean,
) {

    fun equal(): Condition = if (value == null) column.isNull else column.eq(value)

    /** NULL sorts as the largest value, see [orderBy]. */
    @Suppress("UNCHECKED_CAST")
    fun beyond(): Condition {
        val comparable = column as Field<Comparable<Any>>
        return when (direction) {
            SortDirection.ASC -> {
                if (value == null) {
                    DSL.falseCondition()
                } else {
                    comparable.gt(value as Comparable<Any>).or(column.isNull)
                }
            }
            SortDirection.DESC -> {
                if (value == null) {
                    column.isNotNull
                } else {
                    comparable.lt(value as Comparable<Any>)
                }
            }
        }
    }
}

private fun SortDirection.readAs(backwards: Boolean): SortDirection =
    when {
        !backwards -> this
        this == SortDirection.ASC -> SortDirection.DESC
        else -> SortDirection.ASC
    }
