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
import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.TableField

/**
 * How one entity is stored: its table, fields and the infrastructure to query it. The single
 * dependency of [JooqReadAccess], [JooqCreateAccess], [JooqUpdateAccess] and [JooqDeleteAccess].
 *
 * @property dsl builds the queries; create it with
 *   [PersistenceDialect.dsl][dev.buijs.stratastax.persistence.reactive.spi.PersistenceDialect.dsl].
 * @property idField the entity field that identifies a row; its column is [idColumn].
 * @property queryExecutor runs the queries; joins the caller's transaction when there is one (see
 *   [dev.buijs.stratastax.persistence.reactive.api.AmbientQueryExecutor]).
 * @property transactionalExecutor runs every write (and its cascades) in one transaction.
 * @property version the optimistic-lock version column, `null` for last-write-wins.
 * @property softDeleteColumn the soft-delete timestamp: a delete sets it instead of deleting the
 *   row, and a row where it is set counts as deleted for updates and deletes. `null` for hard
 *   deletes. Reads hide soft-deleted rows through a default filter on the field.
 */
public class JooqEntityBinding(
    public val dsl: DSLContext,
    public val table: Table<*>,
    public val fieldMapping: JooqFieldMapping,
    public val idField: EntityField,
    public val queryExecutor: QueryExecutor,
    public val transactionalExecutor: TransactionalExecutor,
    public val queryTranslator: JooqSearchQueryTranslator = JooqSearchQueryTranslator(),
    public val version: VersionColumn? = null,
    public val softDeleteColumn: TableField<*, *>? = null,
) {

    /** The column of [idField]. */
    public val idColumn: TableField<*, *> =
        fieldMapping.field(idField.name).field as? TableField<*, *>
            ?: throw IllegalArgumentException(
                "Id field '${idField.name}' must be bound to a table column",
            )

    init {
        require(idColumn.table == table) {
            "Id field '${idField.name}' is bound to '${idColumn.table?.name}', not to '${table.name}'"
        }
    }
}
