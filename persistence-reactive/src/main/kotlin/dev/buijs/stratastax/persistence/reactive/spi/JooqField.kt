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

import dev.buijs.stratastax.entity.core.spi.FieldMapping
import dev.buijs.stratastax.entity.core.spi.UnknownFieldException
import dev.buijs.stratastax.search.core.api.FilterValue
import org.jooq.Field

/**
 * The column behind one entity field.
 *
 * @property field the column (or expression) the field reads and filters on.
 * @property selectable whether the field is part of the select list of a read.
 * @property defaultFilterValue a fixed filter applied to every read, e.g. `deletedAt = null` to
 *   hide soft-deleted rows.
 */
public open class JooqField<T>(
    public val field: Field<T>,
    public val selectable: Boolean,
    public val defaultFilterValue: FilterValue? = null,
)

/**
 * Every field of an entity by name, and the groups (relations) some of them belong to. A field's
 * name is also the alias it is selected under, so a [dev.buijs.stratastax.persistence.core.spi.RowMapper]
 * reads it by that name.
 */
public open class JooqFieldMapping(
    override val fields: Map<String, JooqField<*>>,
    public val groups: List<JooqFieldGroup> = emptyList(),
) : FieldMapping<JooqField<*>> {

    init {
        val names = groups.map { it.name }

        require(names.size == names.toSet().size) { "Group names must be unique: $names" }

        groups.forEach { group ->
            group.parentName?.let { parent ->
                require(parent in names) { "Group '${group.name}' has unknown parent '$parent'" }
            }

            group.fieldNames.forEach { field ->
                require(field in fields) { "Group '${group.name}' lists unknown field '$field'" }
            }
        }
    }

    /** Every selectable field, aliased as its name. */
    public val allAliasedFields: List<Field<*>> by lazy {
        fields.entries.filter { it.value.selectable }.map { it.value.field.`as`(it.key) }
    }

    /**
     * [field] aliased as the name of the entity field it backs.
     *
     * @throws UnknownFieldException when no entity field is backed by [field].
     */
    public fun aliased(field: Field<*>): Field<*> {
        val entry =
            fields.entries.firstOrNull { it.value.field == field }
                ?: throw UnknownFieldException(field.name)
        return field.`as`(entry.key)
    }
}
