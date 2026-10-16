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
package dev.buijs.stratastax.persistence.reactive.api

import dev.buijs.stratastax.entity.core.api.EntityField
import dev.buijs.stratastax.entity.core.api.SortDirection
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
import dev.buijs.stratastax.search.core.spi.CursorElement
import dev.buijs.stratastax.search.core.spi.FieldGroupRelation
import dev.buijs.stratastax.search.core.spi.SearchQueryTranslator
import org.jooq.Condition
import org.jooq.JSON
import org.jooq.JSONB
import org.jooq.SelectFieldOrAsterisk
import org.jooq.SortField
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

open class JooqSearchQueryTranslator : SearchQueryTranslator<JooqFieldMapping, JooqSearchParts> {

    override fun translate(
        query: SearchQuery,
        mapping: JooqFieldMapping,
    ): JooqSearchParts {
        val tree = JooqGroupTree(mapping.groups)

        val callerCondition = query.filter?.let { translateExpression(it.root, mapping, tree) }

        val defaultConditions = defaultFilterConditions(mapping, tree)
        val condition =
            (listOfNotNull(callerCondition) + defaultConditions).reduceOrNull(Condition::and)
        val orderBy = translateOrderBy(query, mapping, tree)
        val (groupFields, joins) = renderGroups(null, tree, mapping)

        return JooqSearchParts(
            condition = condition,
            orderBy = orderBy,
            selectFields = rootSelectFields(mapping, tree) + groupFields.map { it.asSelectField() },
            joins = joins,
            seekCondition = seekCondition(query, mapping, tree),
        )
    }

    /**
     * A narrowed, projection-scoped translation: like [translate], but the returned
     * [JooqSearchParts.selectFields] is restricted to just [fields] (a projection's own declared
     * field names: root-level fields and fields of top-level 1:1 groups, e.g. `ghostPlayerId`)
     * instead of every selectable field. Reuses [translate]'s exact filter/default-filter/ sort
     * logic unchanged - only the select list narrows. A 1:1 group field keeps the same qualified
     * alias [translate] gives it, so a projection's row mapper reads it under the same key.
     *
     * [renderGroups]'s own `joins` output is reused **unconditionally** (never narrowed) rather
     * than dropped outright - a 1:N group's own `MULTISET` subquery never contributes to this list
     * at all (it's a fully self-contained derived table, see [renderGroups]'s own kdoc on its
     * `OneToMany` branch), so this list only ever contains cheap 1:1 joins. Keeping them all,
     * regardless of which columns end up selected, is what lets `ORDER BY` on a 1:1-group field
     * ([resolvedSortColumn]'s own aliased-table reference) keep resolving correctly even though
     * this projection selects none of that group's own columns - the alternative (computing just
     * the subset of joins a given sort actually needs) would duplicate [renderGroups]'s own
     * parent-chain walk for a cost (a handful of extra `JOIN`s jOOQ elides output for anyway when
     * unreferenced) that isn't the expensive part this projection feature targets in the first
     * place.
     */
    fun translate(
        query: SearchQuery,
        mapping: JooqFieldMapping,
        fields: Set<String>,
    ): JooqSearchParts {
        val tree = JooqGroupTree(mapping.groups)

        val callerCondition = query.filter?.let { translateExpression(it.root, mapping, tree) }

        val defaultConditions = defaultFilterConditions(mapping, tree)
        val condition =
            (listOfNotNull(callerCondition) + defaultConditions).reduceOrNull(Condition::and)
        val orderBy = translateOrderBy(query, mapping, tree)
        val (groupFields, joins) = renderGroups(null, tree, mapping)

        return JooqSearchParts(
            condition = condition,
            orderBy = orderBy,
            selectFields =
                rootSelectFields(mapping, tree, only = fields) +
                    groupFields.filter { it.alias in fields }.map { it.asSelectField() },
            joins = joins,
            seekCondition = seekCondition(query, mapping, tree),
        )
    }

    /**
     * A lightweight `findById`-style translation: skips sort/filter/cursor parsing entirely
     * (there's no [SearchQuery] to derive them from) and goes straight from an already-typed
     * [field]/[value] pair to a `Condition`, reusing exactly the same projection/join/`MULTISET`
     * rendering ([renderGroups]/[rootSelectFields]) and default-filter application
     * ([defaultFilterConditions]) as [translate] - a `fixedValue(...)` field (e.g. excluding
     * soft-deleted rows) applies here too, unconditionally, same as any other query. [field] can
     * belong to a 1:1/1:N group - [wrapForGroup] wraps the equality condition in the same `EXISTS`
     * chain a grouped caller-filter field already gets. [JooqSearchParts.orderBy] is always empty -
     * a single-row lookup by a unique key has nothing to sort.
     *
     * [value] is intentionally untyped ([EntityField] itself carries no Java type parameter, just a
     * [dev.buijs.stratastax.entity.core.api.EntityFieldType] enum) - the caller already has a
     * well-typed Kotlin value (e.g. a `UUID` id) and shouldn't have to round-trip it through
     * [dev.buijs.stratastax.search.core.api.FilterValue] just to get it back out again, unlike
     * [translate]'s caller-supplied-filter path, which genuinely starts from untyped/parsed input.
     */
    @Suppress("UNCHECKED_CAST")
    fun translateById(
        field: EntityField,
        value: Any,
        mapping: JooqFieldMapping,
    ): JooqSearchParts {
        val tree = JooqGroupTree(mapping.groups)

        val column = rawField(field.name, mapping) as org.jooq.Field<Any?>
        val idCondition = wrapForGroup(column.eq(value), field.name, tree)
        val defaultConditions = defaultFilterConditions(mapping, tree)
        val condition = (listOf(idCondition) + defaultConditions).reduceOrNull(Condition::and)

        val (groupFields, joins) = renderGroups(null, tree, mapping)

        return JooqSearchParts(
            condition = condition,
            orderBy = emptyList(),
            selectFields = rootSelectFields(mapping, tree) + groupFields.map { it.asSelectField() },
            joins = joins,
        )
    }

    /**
     * The batch counterpart of [translateById] - selects every row matching [values] at once, for a
     * root-level `createAll`/`updateAll` bulk re-select after a batch write. Same caveats as
     * [translateById]: [JooqSearchParts.orderBy] is always empty (a caller needing a stable order
     * re-sorts the resulting rows itself), and a `fixedValue(...)` field's default filter still
     * applies unconditionally.
     */
    @Suppress("UNCHECKED_CAST")
    fun translateByIds(
        field: EntityField,
        values: Collection<Any>,
        mapping: JooqFieldMapping,
    ): JooqSearchParts {
        val tree = JooqGroupTree(mapping.groups)

        val column = rawField(field.name, mapping) as org.jooq.Field<Any?>
        val idsCondition = wrapForGroup(column.`in`(values), field.name, tree)
        val defaultConditions = defaultFilterConditions(mapping, tree)
        val condition = (listOf(idsCondition) + defaultConditions).reduceOrNull(Condition::and)

        val (groupFields, joins) = renderGroups(null, tree, mapping)

        return JooqSearchParts(
            condition = condition,
            orderBy = emptyList(),
            selectFields = rootSelectFields(mapping, tree) + groupFields.map { it.asSelectField() },
            joins = joins,
        )
    }

    private fun rootSelectFields(
        mapping: JooqFieldMapping,
        tree: JooqGroupTree,
        only: Set<String>? = null,
    ): List<SelectFieldOrAsterisk> =
        mapping.fields.entries
            .filter { (name, field) ->
                field.selectable && tree.groupFor(name) == null && (only == null || name in only)
            }.map { (name, field) -> field.field.`as`(name) }

    /**
     * A `fixedValue(...)` field's condition, applied unconditionally regardless of the caller's own
     * filter - one per field with a [JooqField.defaultFilterValue], wrapped in `EXISTS` via
     * [wrapExists] exactly like a caller-supplied filter on a grouped field already is, so a fixed
     * value on a 1:1/1:N group field works the same way.
     */
    @Suppress("UNCHECKED_CAST")
    private fun defaultFilterConditions(
        mapping: JooqFieldMapping,
        tree: JooqGroupTree,
    ): List<Condition> =
        mapping.fields.entries.mapNotNull { (name, field) ->
            field.defaultFilterValue?.let { fixedValue ->
                val resolved = resolveValue(fixedValue, field.field.type)
                val column = field.field as org.jooq.Field<Any?>
                val base = if (resolved == null) column.isNull else column.eq(resolved)
                wrapExists(base, tree.groupFor(name), tree)
            }
        }

    /**
     * Every field keeps its already-fully-qualified [JooqField]/`mapping.fields` key as its alias,
     * regardless of nesting depth - including inside a 1:N group's own `MULTISET`/JSON row. This
     * matters beyond SQL readability: the generated `XxxSearch` class (gradle-plugin) always
     * exposes a field's *qualified* key, and the same class is meant to be the single, reliable way
     * to read any field off any row, root or nested - a local, stripped alias inside nested rows
     * would silently break that for any hand-written `RowContext` code keying off `XxxSearch`.
     */
    private fun renderGroups(
        parentName: String?,
        tree: JooqGroupTree,
        mapping: JooqFieldMapping,
        resolveParentField: (org.jooq.Field<*>) -> org.jooq.Field<*> = { it },
        // Whether this call is still within the top-level flat SELECT/JOIN
        // scope (root, or nested only through 1:1-in-1:1 chains) as opposed
        // to inside a 1:N group's own independently-scoped `MULTISET`
        // subquery - only the former ever risks the ambiguous-duplicate-join
        // bug this aliasing exists to fix (see the OneToMany branch below,
        // which always recurses with `false`). A group nested inside a 1:N
        // subquery is left un-aliased even when it's itself a plain 1:1
        // relation (e.g. "ghostPlayer" inside "participants") - aliasing it
        // too would only add churn, since that subquery is already
        // independently scoped and can't collide with anything outside it.
        topLevelScope: Boolean = true,
        // Whether an enclosing 1:1 group is itself LEFT JOINed: everything
        // below it must be too, or an absent optional parent (e.g. no
        // playset change) drops the whole row via its child's INNER JOIN.
        underOptionalParent: Boolean = false,
    ): Pair<List<AliasedField>, List<JooqJoin>> {
        val selects = mutableListOf<AliasedField>()
        val joins = mutableListOf<JooqJoin>()

        for (group in tree.childrenOf(parentName)) {
            val (name1, relation, from, to) = group

            // Every non-junction 1:1 group in the top-level scope gets its
            // own table alias (its own already-globally-unique `name`),
            // unconditionally - not just when a collision is detected - so
            // two groups can never emit an ambiguous duplicate
            // `join APP_USER on ...` / `join APP_USER on ...` for the same
            // physical table.
            val aliasedTable =
                if (relation == FieldGroupRelation.OneToOne && topLevelScope) {
                    to.requireTable().`as`(group.tableAlias)
                } else {
                    null
                }

            val resolveOwnField: (org.jooq.Field<*>) -> org.jooq.Field<*> =
                if (aliasedTable != null) {
                    { f ->
                        aliasedTable.field(f)
                            ?: error(
                                "Column '$f' not found on aliased table '$name1' - this should be impossible",
                            )
                    }
                } else {
                    { it }
                }

            // The parent-side join/correlation column (e.g. MATCH.ID, or a
            // column on an *enclosing* aliased 1:1 group's own target table)
            // - resolved through whichever ancestor alias currently applies,
            // regardless of this group's own relation type: even a 1:N
            // group's correlation back to its parent needs this when nested
            // inside an aliased 1:1 ancestor, or it would reference a table
            // name no longer in scope.
            val resolvedFrom = resolveParentField(from)

            val ownFields =
                mapping.fields.entries
                    .filter { (name, field) ->
                        field.selectable && tree.groupFor(name)?.name == name1
                    }.map { (name, field) ->
                        @Suppress("UNCHECKED_CAST")
                        AliasedField(name, resolveOwnField(field.field) as org.jooq.Field<Any?>)
                    }

            // A nullable FK (e.g. no lastEditedByUser set) must not drop the
            // whole row - see JooqJoin.left's own kdoc - nor may a child of an
            // already optional parent.
            val leftJoin =
                relation == FieldGroupRelation.OneToOne &&
                    (underOptionalParent || from.dataType.nullable())

            val (nestedFields, nestedJoins) =
                renderGroups(
                    name1,
                    tree,
                    mapping,
                    resolveOwnField,
                    topLevelScope = topLevelScope && relation == FieldGroupRelation.OneToOne,
                    underOptionalParent = leftJoin,
                )

            when (relation) {
                FieldGroupRelation.OneToOne -> {
                    selects += ownFields
                    selects += nestedFields
                    @Suppress("UNCHECKED_CAST")
                    val resolvedTo = resolveOwnField(to) as org.jooq.Field<Any?>
                    joins +=
                        JooqJoin(
                            // Falls back to the raw, un-aliased table for a
                            // 1:1 group nested inside a 1:N group's own
                            // MULTISET subquery (topLevelScope == false,
                            // aliasedTable == null there) - matches
                            // resolveOwnField/resolvedTo, which degrade to
                            // identity in exactly the same case.
                            aliasedTable ?: to.requireTable(),
                            joinCondition(resolvedFrom, resolvedTo),
                            left = leftJoin,
                        )
                    joins += nestedJoins
                }

                FieldGroupRelation.OneToMany -> {
                    val elementFields = ownFields + nestedFields

                    // Each entry's value must be the *raw*, unaliased
                    // expression, not the AliasedField wrapper it'd become
                    // via asSelectField() - jOOQ only expands an aliased
                    // Field to "expr AS alias" when it's rendered as a
                    // genuine top-level SELECT list item. Used as a function
                    // argument (as jsonEntry's value is here), an aliased
                    // Field instead renders as a bare reference to its own
                    // alias - silently wrong whenever the alias doesn't
                    // happen to also be a real column name in scope (e.g.
                    // "ghostPlayerDisplayName", not a real column) or,
                    // worse, silently *misleading* whenever it does (e.g.
                    // "id" aliasing a UUID public_id column that happens to
                    // share its alias with an unrelated real bigint "id"
                    // column - selects the wrong column instead of erroring).
                    // The key must be rendered as a literal, not a bind
                    // parameter - DSL.jsonEntry(String, Field<T>) binds the
                    // key as an ordinary parameter, and Postgres' extended
                    // query protocol (as used by the reactive Vert.x driver)
                    // can't infer a type for it inside a polymorphic
                    // jsonb_build_object(...) call ("could not determine data
                    // type of parameter $1"). DSL.inline(...) forces it to be
                    // rendered as a literal instead.
                    val entries =
                        elementFields.map { field ->
                            DSL.jsonEntry(DSL.inline(field.alias), field.raw)
                        }

                    val aggregate = DSL.jsonbArrayAgg(DSL.jsonbObject(entries))

                    // A junction/link-table group hops root -> junction ->
                    // target instead of a single direct join - see
                    // JooqFieldGroup.junctionTable's kdoc.
                    val inner =
                        if (group.junctionTable != null) {
                            DSL
                                .select(aggregate)
                                .from(group.junctionTable)
                                .join(to.requireTable())
                                .on(joinCondition(group.junctionTargetColumn!!, to))
                        } else {
                            DSL.select(aggregate).from(to.requireTable())
                        }

                    val joined = nestedJoins.fold(inner) { step, join -> step.applyJoin(join) }

                    val correlation =
                        if (group.junctionTable != null) {
                            joinCondition(resolvedFrom, group.junctionRootColumn!!)
                        } else {
                            joinCondition(resolvedFrom, to)
                        }

                    val filtered = joined.where(correlation)

                    @Suppress("UNCHECKED_CAST")
                    selects +=
                        AliasedField(
                            name1,
                            DSL.coalesce(DSL.field(filtered), DSL.jsonbArray())
                                as org.jooq.Field<Any?>,
                        )
                }
            }
        }

        return selects to joins
    }

    /**
     * An alias name paired with the *raw*, unaliased expression it names - kept separate (rather
     * than eagerly calling `.as(alias)`) because the same field needs two different renderings
     * depending on where it ends up: a genuine top-level SELECT list item wants `expr AS alias`
     * ([asSelectField]), while a `jsonEntry` value inside an ancestor 1:N group's JSON aggregate
     * wants the raw `expr` alone (see the comment at [renderGroups]'s `OneToMany` branch for why
     * using an already-aliased `Field` there is wrong).
     */
    private data class AliasedField(
        val alias: String,
        val raw: org.jooq.Field<Any?>,
    ) {

        fun asSelectField(): SelectFieldOrAsterisk = raw.`as`(alias)
    }

    /**
     * The sort order, with NULL as the largest value whatever the database's default (Postgres
     * agrees: ASC NULLS LAST, DESC NULLS FIRST), so [seekCondition] knows where NULLs are. A page
     * before a cursor reads the order backwards; [JooqReadAccess] puts its rows back.
     */
    private fun translateOrderBy(
        query: SearchQuery,
        mapping: JooqFieldMapping,
        tree: JooqGroupTree,
    ): List<SortField<*>> {
        val backwards = query.cursor?.direction == CursorDirection.BEFORE
        return query.sort.fields.map {
            val column = resolvedSortColumn(it.field.name, mapping, tree)
            when (it.direction.readAs(backwards)) {
                SortDirection.ASC -> column.asc().nullsLast()
                SortDirection.DESC -> column.desc().nullsFirst()
            }
        }
    }

    /**
     * The rows past the cursor in the order [translateOrderBy] reads: past the cursor's value of
     * the first sort field, or equal to it and past the value of the second, and so on. The
     * tiebreaker the sort always ends with makes the position unique.
     */
    @Suppress("UNCHECKED_CAST")
    private fun seekCondition(
        query: SearchQuery,
        mapping: JooqFieldMapping,
        tree: JooqGroupTree,
    ): Condition? {
        val cursor = query.cursor ?: return null
        val fields = query.sort.fields
        if (cursor.values.size != fields.size) {
            throw SearchException("Cursor does not match the current sort criteria")
        }

        val backwards = cursor.direction == CursorDirection.BEFORE
        val keys =
            fields.zip(cursor.values).map { (sortField, element) ->
                val column =
                    resolvedSortColumn(sortField.field.name, mapping, tree) as org.jooq.Field<Any?>
                SeekKey(
                    column,
                    sortField.direction.readAs(backwards),
                    cursorValue(element, column, sortField.field.name),
                )
            }

        return DSL.or(
            keys.indices.map { index ->
                DSL.and(keys.take(index).map { it.equal() } + keys[index].beyond())
            },
        )
    }

    private class SeekKey(
        val column: org.jooq.Field<Any?>,
        val direction: SortDirection,
        val value: Any?,
    ) {

        fun equal(): Condition = if (value == null) column.isNull else column.eq(value)

        /** NULL is the largest value, see [translateOrderBy]. */
        @Suppress("UNCHECKED_CAST")
        fun beyond(): Condition {
            val comparable = column as org.jooq.Field<Comparable<Any>>
            return when (direction) {
                SortDirection.ASC -> {
                    if (value == null) {
                        DSL.falseCondition()
                    } else {
                        comparable.gt(value as Comparable<Any>).or(column.isNull)
                    }
                }
                SortDirection.DESC -> {
                    if (value == null) column.isNotNull else comparable.lt(value as Comparable<Any>)
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

    /** A cursor element as a value of [column]'s type, like [resolveValue] does for a filter. */
    private fun cursorValue(
        element: CursorElement,
        column: org.jooq.Field<*>,
        fieldName: String,
    ): Any? =
        when (element) {
            is CursorElement.NullValue -> {
                null
            }
            is CursorElement.StringValue -> {
                if (column.type.isEnum) {
                    convertEnum(element.value, column.type)
                } else {
                    column.dataType.convert(element.value)
                }
            }
            is CursorElement.NumberValue -> {
                convertNumber(element.value, column.type)
            }
            is CursorElement.BooleanValue -> {
                element.value
            }
            is CursorElement.TimestampValue -> {
                convertDateTime(element.value, column.type)
            }
            is CursorElement.ListValue -> {
                throw SearchException("Field '$fieldName' cannot be a cursor position")
            }
        }

    /**
     * ORDER BY on a grouped field must reference the same alias [renderGroups] joined that group's
     * table under - a raw, un-aliased reference would resolve against a table name no longer in
     * scope once that table is joined under an alias. Only needs the field's *own* owning group's
     * alias, not the full parent chain - a sortable field can only ever live at the root or inside
     * a non-junction 1:1 chain (config validation already forbids marking a field sortable under a
     * 1:N ancestor), so [renderGroups]'s own recursive parent-alias threading doesn't apply here.
     */
    private fun resolvedSortColumn(
        name: String,
        mapping: JooqFieldMapping,
        tree: JooqGroupTree,
    ): org.jooq.Field<*> {
        val raw = rawField(name, mapping)
        val group = tree.groupFor(name) ?: return raw
        if (group.relation != FieldGroupRelation.OneToOne) return raw
        val aliasedTable = group.to.requireTable().`as`(group.tableAlias)
        return aliasedTable.field(raw)
            ?: error(
                "Column '$raw' not found on aliased table '${group.tableAlias}' - this should be impossible",
            )
    }

    private fun translateExpression(
        expression: FilterExpression,
        mapping: JooqFieldMapping,
        tree: JooqGroupTree,
    ): Condition =
        when (expression) {
            is AndExpression -> {
                DSL.and(expression.children.map { translateExpression(it, mapping, tree) })
            }
            is OrExpression -> {
                DSL.or(expression.children.map { translateExpression(it, mapping, tree) })
            }
            is ComparisonExpression -> {
                wrapForGroup(comparisonCondition(expression, mapping), expression.field.name, tree)
            }
            is InExpression -> {
                wrapForGroup(inCondition(expression, mapping), expression.field.name, tree)
            }
            is LikeExpression -> {
                wrapForGroup(likeCondition(expression, mapping), expression.field.name, tree)
            }
        }

    private fun wrapForGroup(
        condition: Condition,
        fieldName: String,
        tree: JooqGroupTree,
    ): Condition = wrapExists(condition, tree.groupFor(fieldName), tree)

    private fun wrapExists(
        condition: Condition,
        group: JooqFieldGroup?,
        tree: JooqGroupTree,
    ): Condition {
        if (group == null) return condition
        // A junction/link-table group (see JooqFieldGroup.junctionTable's
        // kdoc) needs the same root -> junction -> target hop here as
        // renderGroups' OneToMany branch does - there's no direct
        // root-to-target relation to EXISTS against.
        val exists =
            if (group.junctionTable != null) {
                DSL.exists(
                    DSL
                        .selectOne()
                        .from(group.junctionTable)
                        .join(group.to.requireTable())
                        .on(joinCondition(group.junctionTargetColumn!!, group.to))
                        .where(
                            joinCondition(group.from, group.junctionRootColumn!!).and(condition),
                        ),
                )
            } else {
                DSL.exists(
                    DSL
                        .selectOne()
                        .from(group.to.requireTable())
                        .where(joinCondition(group.from, group.to).and(condition)),
                )
            }

        return wrapExists(exists, tree.parentOf(group), tree)
    }

    @Suppress("UNCHECKED_CAST")
    private fun joinCondition(
        from: org.jooq.Field<*>,
        to: org.jooq.Field<*>,
    ): Condition = (from as org.jooq.Field<Any?>).eq(to as org.jooq.Field<Any?>)

    private fun TableField<*, *>.requireTable(): Table<*> =
        table ?: error("Column '$name' has no owning table - cannot build a join/EXISTS for it")

    @Suppress("UNCHECKED_CAST")
    private fun comparisonCondition(
        expression: ComparisonExpression,
        mapping: JooqFieldMapping,
    ): Condition {
        val rawColumn = rawField(expression.field.name, mapping)
        val column = rawColumn as org.jooq.Field<Any?>
        val value = resolveValue(expression.value, rawColumn.type)

        return when (expression.operator) {
            ComparisonOperator.EQUALS -> {
                if (value == null) column.isNull else column.eq(value)
            }

            ComparisonOperator.NOT_EQUALS -> {
                if (value == null) column.isNotNull else column.ne(value)
            }

            ComparisonOperator.GREATER_THAN -> {
                (column as org.jooq.Field<Comparable<Any>>).gt(
                    requireNonNullComparable(value, expression),
                )
            }

            ComparisonOperator.GREATER_THAN_OR_EQUAL -> {
                (column as org.jooq.Field<Comparable<Any>>).ge(
                    requireNonNullComparable(value, expression),
                )
            }

            ComparisonOperator.LESS_THAN -> {
                (column as org.jooq.Field<Comparable<Any>>).lt(
                    requireNonNullComparable(value, expression),
                )
            }

            ComparisonOperator.LESS_THAN_OR_EQUAL -> {
                (column as org.jooq.Field<Comparable<Any>>).le(
                    requireNonNullComparable(value, expression),
                )
            }

            // Only exists so FilterParser can decide to emit a LikeExpression -
            // should never reach here as the operator on a
            // ComparisonExpression.
            ComparisonOperator.LIKE,
            ComparisonOperator.ILIKE,
            -> {
                error(
                    "${expression.operator} must be represented as LikeExpression, not ComparisonExpression (field '${expression.field.name}')",
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun inCondition(
        expression: InExpression,
        mapping: JooqFieldMapping,
    ): Condition {
        val rawColumn = rawField(expression.field.name, mapping)
        val column = rawColumn as org.jooq.Field<Any?>
        val values = expression.values.map { resolveValue(it, rawColumn.type) }

        return when (expression.operator) {
            InOperator.IN -> column.`in`(values)
            InOperator.OUT -> column.notIn(values)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun likeCondition(
        expression: LikeExpression,
        mapping: JooqFieldMapping,
    ): Condition {
        // Safe without a type-conversion step: requireTypeMatches() at
        // config-build
        // time already guarantees a STRING field's column has Java type String.
        val column = rawField(expression.field.name, mapping) as org.jooq.Field<String>
        return if (expression.ignoreCase) {
            // ignoreCase patterns come from containsIgnoreCase, which escapes with a backslash.
            column.likeIgnoreCase(expression.pattern, '\\')
        } else {
            column.like(expression.pattern)
        }
    }

    private fun requireNonNullComparable(
        value: Any?,
        expression: ComparisonExpression,
    ): Comparable<Any> {
        @Suppress("UNCHECKED_CAST")
        return value as? Comparable<Any>
            ?: error(
                "Operator '${expression.operator}' on field '${expression.field.name}' does not support NULL",
            )
    }

    /**
     * Converts a parsed FilterValue into whatever Java type the target column actually needs -
     * FilterValue.NumberValue is always a BigDecimal and FilterValue.DateTimeValue is always a UTC
     * Instant (see FilterValue's own kdoc), but the column itself can legitimately be Field<Int>,
     * Field<LocalDate>, a generated enum class, etc. requireTypeMatches() in
     * JooqSearchConfigurationDsl guarantees targetType is one of the types handled below - the
     * `error(...)` branches here are a "this should be unreachable" safety net, not expected
     * user-facing validation.
     */
    private fun resolveValue(
        value: FilterValue,
        targetType: Class<*>,
    ): Any? =
        when (value) {
            is FilterValue.StringValue -> {
                value.value
            }
            is FilterValue.NumberValue -> {
                convertNumber(value.value, targetType)
            }
            is FilterValue.BooleanValue -> {
                value.value
            }
            is FilterValue.DateTimeValue -> {
                convertDateTime(value.value, targetType)
            }
            is FilterValue.DateValue -> {
                convertDate(value.value, targetType)
            }
            is FilterValue.EnumValue -> {
                convertEnum(value.value, targetType)
            }
            is FilterValue.UuidValue -> {
                value.value
            }
            is FilterValue.JsonValue -> {
                if (targetType == JSON::class.java) {
                    JSON.valueOf(value.value)
                } else {
                    JSONB.valueOf(value.value)
                }
            }
            is FilterValue.NullValue -> {
                null
            }
        }

    private fun convertNumber(
        value: BigDecimal,
        targetType: Class<*>,
    ): Any =
        when (targetType) {
            BigDecimal::class.java -> {
                value
            }
            Int::class.javaObjectType,
            Int::class.javaPrimitiveType,
            -> {
                value.intValueExact()
            }
            Long::class.javaObjectType,
            Long::class.javaPrimitiveType,
            -> {
                value.longValueExact()
            }
            Double::class.javaObjectType,
            Double::class.javaPrimitiveType,
            -> {
                value.toDouble()
            }
            Float::class.javaObjectType,
            Float::class.javaPrimitiveType,
            -> {
                value.toFloat()
            }
            Short::class.javaObjectType,
            Short::class.javaPrimitiveType,
            -> {
                value.shortValueExact()
            }
            else -> {
                error(
                    "No NUMBER -> ${targetType.simpleName} conversion implemented - this should " +
                        "have " +
                        "been caught by requireTypeMatches() when the configuration was built",
                )
            }
        }

    /**
     * DateTimeValue is always UTC (see FilterValue's kdoc), so UTC is used for every target type
     * below.
     */

    /**
     * A date without a time is compared as-is to a LocalDate column, and as the start of that day
     * (UTC) to any date-time column.
     */
    private fun convertDate(
        value: LocalDate,
        targetType: Class<*>,
    ): Any =
        if (targetType == LocalDate::class.java) {
            value
        } else {
            convertDateTime(value.atStartOfDay(ZoneOffset.UTC).toInstant(), targetType)
        }

    private fun convertDateTime(
        value: Instant,
        targetType: Class<*>,
    ): Any =
        when (targetType) {
            Instant::class.java -> {
                value
            }
            LocalDateTime::class.java -> {
                LocalDateTime.ofInstant(value, ZoneOffset.UTC)
            }
            LocalDate::class.java -> {
                LocalDate.ofInstant(value, ZoneOffset.UTC)
            }
            OffsetDateTime::class.java -> {
                value.atOffset(ZoneOffset.UTC)
            }
            Timestamp::class.java -> {
                Timestamp.from(value)
            }
            else -> {
                error(
                    "No DATE/DATETIME -> ${targetType.simpleName} conversion implemented - this " +
                        "should have " +
                        "been caught by requireTypeMatches() when the configuration was built",
                )
            }
        }

    private fun convertEnum(
        value: String,
        targetType: Class<*>,
    ): Any =
        when {
            targetType == String::class.java -> {
                value
            }
            targetType.isEnum -> {
                @Suppress("UNCHECKED_CAST")
                java.lang.Enum.valueOf(targetType as Class<out Enum<*>>, value)
            }
            else -> {
                error(
                    "No ENUM -> ${targetType.simpleName} conversion implemented - this should " +
                        "have " +
                        "been caught by requireTypeMatches() when the configuration was built",
                )
            }
        }

    private fun rawField(
        name: String,
        mapping: JooqFieldMapping,
    ): org.jooq.Field<*> = mapping.field(name).field
}
