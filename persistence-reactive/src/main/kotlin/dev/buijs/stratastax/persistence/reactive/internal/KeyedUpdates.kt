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

import dev.buijs.stratastax.persistence.core.spi.RowContext
import org.jooq.Condition
import org.jooq.DataType
import org.jooq.Field
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL

/**
 * One row to update: the row whose key equals [key] (and, when the update is versioned, whose
 * version equals [expectedVersion]) gets [values].
 */
internal class KeyedRow(
    val key: Any?,
    val expectedVersion: Any?,
    val values: RowValues,
)

/**
 * Updates [rows] of [table], identified by [key]: rows writing the same columns share one
 * statement - a plain `UPDATE` for a single row, `UPDATE ... FROM (VALUES ...)` for several.
 * [version] adds the per-row version check and its increment; [condition] restricts every update
 * further (e.g. to rows that are not soft-deleted).
 *
 * Returns the updated rows - their key as [KEY_ALIAS] and every [returned] column as
 * [returnedAlias] - in no particular order; a row whose key (or version) did not match is absent.
 */
internal suspend fun RowWriter.updateRows(
    table: Table<*>,
    key: TableField<*, *>,
    rows: List<KeyedRow>,
    version: Pair<TableField<*, *>, Field<*>>? = null,
    condition: Condition? = null,
    returned: List<TableField<*, *>> = emptyList(),
): List<RowContext> {
    val returning =
        listOf(key.`as`(KEY_ALIAS)) + returned.distinct().map { it.`as`(returnedAlias(it)) }

    return rows.groupBy { it.values.keys }.values.flatMap { sameColumns ->
        val columns =
            sameColumns
                .first()
                .values.keys
                .toList()
        val perStatement = rowsPerStatement(columns.size + 2)
        sameColumns.chunked(perStatement).flatMap { chunk ->
            val query =
                if (chunk.size == 1) {
                    singleRowUpdate(table, key, chunk.single(), version, condition)
                } else {
                    multiRowUpdate(table, key, columns, chunk, version, condition)
                }

            executor.executeAndGetAll(query.returning(returning))
        }
    }
}

private fun RowWriter.singleRowUpdate(
    table: Table<*>,
    key: TableField<*, *>,
    row: KeyedRow,
    version: Pair<TableField<*, *>, Field<*>>?,
    condition: Condition?,
) = dsl
    .update(table)
    .set(row.values + listOfNotNull(version).toMap())
    .where(
        allOf(
            key.untyped().eq(row.key),
            version?.first?.untyped()?.eq(row.expectedVersion),
            condition,
        ),
    )

/**
 * `UPDATE table SET c = v.c, ... FROM (VALUES (key, version, c, ...), ...) AS v WHERE table.key =
 * v.key [AND table.version = v.version]` - one statement for every row, each value cast to its
 * column's type so Postgres does not have to infer the types of the `VALUES` list.
 */
private fun RowWriter.multiRowUpdate(
    table: Table<*>,
    key: TableField<*, *>,
    columns: List<Field<*>>,
    rows: List<KeyedRow>,
    version: Pair<TableField<*, *>, Field<*>>?,
    condition: Condition?,
): org.jooq.UpdateConditionStep<*> {
    val versionColumn = version?.first
    val leading = listOfNotNull("stx_key", versionColumn?.let { "stx_version" })
    val names = leading + columns.indices.map { "stx_c$it" }

    val values =
        rows.map { row ->
            DSL.row(
                listOfNotNull(
                    typed(row.key, key.dataType),
                    versionColumn?.let { typed(row.expectedVersion, it.dataType) },
                ) +
                    columns.map { typed(row.values[it], it.dataType) },
            )
        }

    val source = DSL.values(*values.toTypedArray()).`as`("stx_rows", *names.toTypedArray())

    fun sourceColumn(
        name: String,
        type: DataType<*>,
    ): Field<Any?> = DSL.field(DSL.name("stx_rows", name), type).untyped()

    val assignments: Map<Field<*>, Any?> =
        columns
            .mapIndexed {
                index,
                column,
                ->
                column to sourceColumn("stx_c$index", column.dataType)
            }.toMap() +
            listOfNotNull(version).toMap()
    return dsl
        .update(table)
        .set(assignments)
        .from(source)
        .where(
            allOf(
                key.untyped().eq(sourceColumn("stx_key", key.dataType)),
                versionColumn?.let { it.untyped().eq(sourceColumn("stx_version", it.dataType)) },
                condition,
            ),
        )
}

@Suppress("UNCHECKED_CAST")
private fun DataType<*>.untypedType(): DataType<Any?> = this as DataType<Any?>

/** [value] as a bind value cast to [type] (or the expression itself, when it already is one). */
private fun typed(
    value: Any?,
    type: DataType<*>,
): Field<*> =
    value as? Field<*> ?: DSL.cast(DSL.`val`(value, type.untypedType()), type.untypedType())
