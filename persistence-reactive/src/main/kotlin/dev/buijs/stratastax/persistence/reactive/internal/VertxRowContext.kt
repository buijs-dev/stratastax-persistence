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
import dev.buijs.stratastax.persistence.core.spi.UnknownColumnException
import dev.buijs.stratastax.persistence.reactive.spi.PersistenceDialect
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.mutiny.sqlclient.Row
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The column positions of one result set, shared by all its rows. A name is matched exactly first,
 * then by its [PersistenceDialect.columnKey] (the database folds unquoted identifiers), and resolved
 * once instead of on every read. Not thread-safe: a result set is mapped on one coroutine.
 */
internal class ColumnIndex(
    private val columnNames: List<String>,
    private val dialect: PersistenceDialect,
) {

    private val byKey: Map<String, Int> =
        columnNames
            .withIndex()
            .groupBy({ dialect.columnKey(it.value) }, { it.index })
            .mapValues { it.value.first() }

    private val positions = HashMap<String, Int>()

    /**
     * The position of column [name].
     *
     * @throws UnknownColumnException when the result set has no such column.
     */
    fun of(name: String): Int =
        positions.getOrPut(name) {
            columnNames.indexOf(name).takeIf { it >= 0 }
                ?: byKey[dialect.columnKey(name)]
                ?: throw UnknownColumnException(name, columnNames)
        }

    companion object {

        /** The index of a single [row], read on its own. */
        fun of(
            row: Row,
            dialect: PersistenceDialect,
        ): ColumnIndex = ColumnIndex((0 until row.size()).map(row::getColumnName), dialect)
    }
}

/**
 * A [RowContext] over a Vert.x reactive [Row]: columns are read by position through [index], which
 * every row of a result set shares. JSON columns are decoded to plain maps and lists.
 */
internal class VertxRowContext(
    private val row: Row,
    private val index: ColumnIndex,
) : RowContext {

    override fun uuidOrNull(name: String): UUID? = read(name, row::getUUID)

    override fun uuidList(name: String): List<UUID> = readArray(name, row::getArrayOfUUIDs)

    override fun offsetDateTimeOrNull(name: String): OffsetDateTime? =
        read(name, row::getOffsetDateTime)

    override fun offsetDateTimeList(name: String): List<OffsetDateTime> =
        readArray(name, row::getArrayOfOffsetDateTimes)

    override fun localDateOrNull(name: String): LocalDate? = read(name, row::getLocalDate)

    override fun localDateList(name: String): List<LocalDate> =
        readArray(name, row::getArrayOfLocalDates)

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
        readArray(name, row::getArrayOfNumerics).map { it.bigDecimalValue() }

    override fun objectListOrNull(name: String): List<Map<String, Any?>>? =
        read(name, row::getJsonArray)?.map { element ->
            val json =
                element as? JsonObject
                    ?: throw IllegalArgumentException("Column '$name' holds a non-object element")
            plainObject(json)
        }

    override fun mapOrNull(name: String): Map<String, Any?>? =
        read(name, row::getJsonObject)?.let(::plainObject)

    override fun rawOrNull(name: String): Any? = read(name, row::getValue)

    private inline fun <T> read(
        name: String,
        getter: (Int) -> T?,
    ): T? {
        val position = index.of(name)
        return try {
            getter(position)
        } catch (e: ClassCastException) {
            throw IllegalArgumentException(
                "Column '$name' cannot be read as the requested type: ${e.message}",
                e,
            )
        }
    }

    private inline fun <T : Any> readArray(
        name: String,
        getter: (Int) -> Array<T?>?,
    ): List<T> =
        read(name, getter)
            ?.map {
                it
                    ?: throw IllegalArgumentException("Column '$name' contains a null element")
            }.orEmpty()
}

/** [json] with every nested Vert.x JSON value converted to plain maps and lists. */
private fun plainObject(json: JsonObject): Map<String, Any?> =
    json.map.mapValues { plain(it.value) }

private fun plain(value: Any?): Any? =
    when (value) {
        is JsonObject -> {
            plainObject(value)
        }
        is JsonArray -> {
            value.list.map(::plain)
        }
        is Map<*, *> -> {
            value.entries.associate { (key, element) -> key.toString() to plain(element) }
        }
        is List<*> -> {
            value.map(::plain)
        }
        else -> {
            value
        }
    }
