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

import dev.buijs.stratastax.persistence.reactive.spi.JooqFieldGroup

/** Lookups over the groups of a mapping, by field, by parent and up the ancestor chain. */
internal class GroupTree(
    groups: List<JooqFieldGroup>,
) {

    private val byName = groups.associateBy { it.name }

    private val byFieldName =
        groups
            .flatMap { group -> group.fieldNames.map { it to group } }
            .toMap()

    private val byParentName = groups.groupBy { it.parentName }

    /** The group field [fieldName] belongs to, `null` for a root field. */
    fun groupFor(fieldName: String): JooqFieldGroup? = byFieldName[fieldName]

    /** The group [group] is nested in, `null` for a group on the root. */
    fun parentOf(group: JooqFieldGroup): JooqFieldGroup? = group.parentName?.let(byName::get)

    /** The groups nested directly in [parentName] (`null`: the root). */
    fun childrenOf(parentName: String?): List<JooqFieldGroup> = byParentName[parentName].orEmpty()

    /** The groups from the root down to [group], [group] last. */
    fun chainTo(group: JooqFieldGroup): List<JooqFieldGroup> =
        generateSequence(group, ::parentOf).toList().asReversed()
}
