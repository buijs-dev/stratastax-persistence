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
package dev.buijs.stratastax.persistence.reactive.app

import dev.buijs.stratastax.persistence.core.spi.RowContext
import io.vertx.core.json.JsonObject
import io.vertx.mutiny.sqlclient.Row
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The column positions of one result set, shared by all its rows: each requested name is resolved
 * once - lowercased, as Postgres/jOOQ hand back unquoted identifiers - instead of on every read of
 * every row. Not thread-safe; a result set's rows are mapped on one coroutine.
 *
 * @param columnNames the result set's columns, in order
 */
class ColumnIndex(
    private val columnNames: List<String>,
) {

    private val positions = HashMap<String, Int>()

    /** The position of column [name], `-1` when the result set has no such column. */
    fun of(name: String): Int = positions.getOrPut(name) { columnNames.indexOf(name.lowercase()) }

    companion object {

        /** The index of a single [row], for a row read on its own. */
        fun of(row: Row): ColumnIndex = ColumnIndex((0 until row.size()).map(row::getColumnName))
    }
}

/**
 * A [RowContext] adapter over a Vert.x Mutiny reactive-Postgres [Row] - the one driver-specific
 * implementation this module exists to provide, so that `RowMapper` implementations (generated or
 * hand-written) never need to know which reactive client produced a row. Columns are read by
 * position through [index], shared by every row of the result set; a column the result set doesn't
 * have reads as `null`.
 */
@Suppress("UNCHECKED_CAST")
class VertxRowContext(
    private val row: Row,
    private val index: ColumnIndex = ColumnIndex.of(row),
) : RowContext {

    override fun uuidOrNull(name: String): UUID? = read(name, row::getUUID)

    override fun uuidList(name: String): List<UUID> = readArray(name, row::getArrayOfUUIDs)

    override fun offsetDateTimeOrNull(name: String): OffsetDateTime? =
        read(name, row::getOffsetDateTime)

    override fun offsetDateTimeList(name: String): List<OffsetDateTime> =
        readArray(name, row::getArrayOfOffsetDateTimes)

    override fun booleanOrNull(name: String): Boolean? = read(name, row::getBoolean)

    override fun booleanList(name: String): List<Boolean> = readArray(name, row::getArrayOfBooleans)

    override fun stringOrNull(name: String): String? = read(name, row::getString)

    override fun stringList(name: String): List<String> = readArray(name, row::getArrayOfStrings)

    override fun longOrNull(name: String): Long? = read(name, row::getLong)

    override fun longList(name: String): List<Long> = readArray(name, row::getArrayOfLongs)

    override fun integerOrNull(name: String): Int? = read(name, row::getInteger)

    override fun integerList(name: String): List<Int> = readArray(name, row::getArrayOfIntegers)

    override fun decimalOrNull(name: String): BigDecimal? =
        read(name, row::getNumeric)?.bigDecimalValue()

    override fun decimalList(name: String): List<BigDecimal> =
        read(name, row::getArrayOfNumerics)?.filterNotNull()?.map { it.bigDecimalValue() }.orEmpty()

    /**
     * Decodes a `jsonb`/json array column (a 1:N DSL group's `MULTISET`-style aggregate, see
     * [RowContext.objectList]'s kdoc) via Vert.x's own `Row.getJsonArray`.
     */
    override fun objectListOrNull(name: String): List<Map<String, Any?>>? =
        read(name, row::getJsonArray)?.map { (it as JsonObject).map as Map<String, Any?> }

    /**
     * Decodes a `jsonb`/json *object* column (e.g. a free-form attributes bag) via Vert.x's own
     * `Row.getJsonObject` - the single-object counterpart of [objectListOrNull]'s own JSON array
     * decoding.
     */
    override fun mapOrNull(name: String): Map<String, Any?>? = read(name, row::getJsonObject)?.map

    override fun rawOrNull(name: String): Any? = read(name, row::getValue)

    private inline fun <T> read(
        name: String,
        getter: (Int) -> T?,
    ): T? {
        val position = index.of(name)
        return if (position < 0) null else getter(position)
    }

    private fun <T : Any> readArray(
        name: String,
        getter: (Int) -> Array<T?>?,
    ): List<T> = read(name, getter)?.filterNotNull().orEmpty()
}
