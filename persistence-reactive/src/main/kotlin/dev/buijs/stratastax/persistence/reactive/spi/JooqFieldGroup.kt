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

import dev.buijs.stratastax.search.core.spi.FieldGroupRelation
import org.jooq.Table
import org.jooq.TableField

/**
 * A group of entity fields that live in a related table, e.g. `game` (one-to-one) or
 * `participants` (one-to-many). A group may be nested in another group, named by [parentName].
 *
 * Reads select a one-to-one group's fields through a join, and a one-to-many or junction group's
 * fields as one JSON array per root row (read with
 * [dev.buijs.stratastax.persistence.core.spi.RowContext.objectList]). A filter on a group field
 * becomes an `EXISTS` subquery.
 */
public sealed interface JooqFieldGroup {

    /** Unique name of the group; also its alias, and the column name of a one-to-many array. */
    public val name: String

    /** The names (keys in [JooqFieldMapping.fields]) of the fields that belong to this group. */
    public val fieldNames: List<String>

    /** The name of the group this group is nested in, `null` for a group on the root entity. */
    public val parentName: String?

    /** The parent-side column of the relation (on the root table or the parent group's table). */
    public val from: TableField<*, *>

    /** The relation kind, as the search layer sees it. */
    public val relation: FieldGroupRelation
}

/**
 * A one-to-one relation: [from] (e.g. `PLAYSET.GAME_ID`) references [to] (e.g. `GAME.ID`). The
 * join is a `LEFT JOIN` when [from] is nullable or a parent group is optional, so a missing
 * relation leaves its fields `null` instead of dropping the row.
 *
 * @property identityColumn the related table's public identity column (e.g. `GAME.PUBLIC_ID`),
 *   when commands reference the relation by it.
 */
public data class OneToOneGroup(
    override val name: String,
    override val from: TableField<*, *>,
    val to: TableField<*, *>,
    override val fieldNames: List<String> = emptyList(),
    override val parentName: String? = null,
    val identityColumn: TableField<*, *>? = null,
) : JooqFieldGroup {

    override val relation: FieldGroupRelation
        get() = FieldGroupRelation.OneToOne
}

/**
 * A one-to-many relation over a foreign key on the child table: [to] (e.g.
 * `MATCH_PARTICIPANT.MATCH_ID`) references [from] (e.g. `MATCH.ID`).
 *
 * @property childIdentityColumn the child's own identity column; with no primary key on the child
 *   table it orders the elements of the JSON array.
 * @property softDeleteColumn the child's soft-delete timestamp: children where it is set are left
 *   out of reads.
 */
public data class OneToManyGroup(
    override val name: String,
    override val from: TableField<*, *>,
    val to: TableField<*, *>,
    override val fieldNames: List<String> = emptyList(),
    override val parentName: String? = null,
    val childIdentityColumn: TableField<*, *>? = null,
    val softDeleteColumn: TableField<*, *>? = null,
) : JooqFieldGroup {

    override val relation: FieldGroupRelation
        get() = FieldGroupRelation.OneToMany
}

/**
 * A many-to-many relation through a link table: [from] (e.g. `MATCH.ID`) is matched by
 * [junctionRootColumn] (e.g. `MATCH_TAG.MATCH_ID`), and [junctionTargetColumn] (e.g.
 * `MATCH_TAG.TAG_ID`) references [to] (e.g. `TAG.ID`).
 *
 * @property targetIdentityColumn the target table's public identity column (e.g. `TAG.PUBLIC_ID`),
 *   when commands reference targets by it.
 */
public data class JunctionGroup(
    override val name: String,
    override val from: TableField<*, *>,
    val junctionTable: Table<*>,
    val junctionRootColumn: TableField<*, *>,
    val junctionTargetColumn: TableField<*, *>,
    val to: TableField<*, *>,
    override val fieldNames: List<String> = emptyList(),
    override val parentName: String? = null,
    val targetIdentityColumn: TableField<*, *>? = null,
) : JooqFieldGroup {

    override val relation: FieldGroupRelation
        get() = FieldGroupRelation.OneToMany
}
