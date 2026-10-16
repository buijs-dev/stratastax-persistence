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

/**
 * Cheap lookup structure over a flat [JooqFieldGroup] list.
 *
 * Both lookups resolve via fields the DSL builders set explicitly at construction time, never by
 * parsing [JooqFieldGroup.name]'s format (dotted, camelCase, or otherwise - that's an internal
 * DSL-generation detail, not a contract this class can rely on):
 * - [groupFor] uses each group's [JooqFieldGroup.fieldNames] - a direct reverse index - rather than
 *   slicing a field name's dotted segments, since a field's registered name is camelCase (see
 *   [dev.buijs.stratastax.search.core.spi.toSearchFieldName]) and no longer carries its group path.
 * - [parentOf]/[childrenOf] use each group's [JooqFieldGroup.parentName], which
 *   `JooqOneToOneGroupBuilder`/`JooqOneToManyGroupBuilder` set directly to the enclosing group's
 *   own name (or `null` for a group declared directly on the root entity) - not derived from
 *   [JooqFieldGroup.name] at all.
 */
internal class JooqGroupTree(
    groups: List<JooqFieldGroup>,
) {

    private val byName = groups.associateBy { it.name }

    private val byFieldName =
        groups.flatMap { group -> group.fieldNames.map { it to group } }.toMap()

    private val byParentName = groups.groupBy { it.parentName }

    fun groupFor(fieldName: String): JooqFieldGroup? = byFieldName[fieldName]

    fun parentOf(group: JooqFieldGroup): JooqFieldGroup? = group.parentName?.let { byName[it] }

    fun childrenOf(parentName: String?): List<JooqFieldGroup> = byParentName[parentName].orEmpty()
}
