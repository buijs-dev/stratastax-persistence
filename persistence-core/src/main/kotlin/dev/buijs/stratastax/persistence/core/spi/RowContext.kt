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
package dev.buijs.stratastax.persistence.core.spi

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Named, typed access to the columns of one row, independent of where the row came from (a
 * database driver, a JSON object, a CSV record). A [RowMapper] reads a row through this interface.
 *
 * Every type has the same family of accessors:
 * - `xOrNull(name)`: the value, or `null` when the column is NULL;
 * - `x(name)`: the value, throwing [IllegalStateException] when the column is NULL;
 * - `xList(name)` / `xSet(name)`: a multi-valued column, empty when NULL.
 *
 * Reading a column the row does not have is a programming error and throws
 * [UnknownColumnException], it never reads as `null`. A value that cannot be read as the requested
 * type throws [IllegalArgumentException].
 */
public interface RowContext {

    public fun uuid(name: String): UUID = uuidOrNull(name) ?: nullColumn(name)

    public fun uuidOrNull(name: String): UUID?

    public fun uuidList(name: String): List<UUID>

    public fun uuidSet(name: String): Set<UUID> = uuidList(name).toSet()

    public fun offsetDateTime(name: String): OffsetDateTime =
        offsetDateTimeOrNull(name) ?: nullColumn(name)

    public fun offsetDateTimeOrNull(name: String): OffsetDateTime?

    public fun offsetDateTimeList(name: String): List<OffsetDateTime>

    public fun offsetDateTimeSet(name: String): Set<OffsetDateTime> =
        offsetDateTimeList(name).toSet()

    public fun localDate(name: String): LocalDate = localDateOrNull(name) ?: nullColumn(name)

    public fun localDateOrNull(name: String): LocalDate?

    public fun localDateList(name: String): List<LocalDate>

    public fun localDateSet(name: String): Set<LocalDate> = localDateList(name).toSet()

    public fun boolean(name: String): Boolean = booleanOrNull(name) ?: nullColumn(name)

    public fun booleanOrNull(name: String): Boolean?

    public fun booleanList(name: String): List<Boolean>

    public fun booleanSet(name: String): Set<Boolean> = booleanList(name).toSet()

    public fun string(name: String): String = stringOrNull(name) ?: nullColumn(name)

    public fun stringOrNull(name: String): String?

    public fun stringList(name: String): List<String>

    public fun stringSet(name: String): Set<String> = stringList(name).toSet()

    public fun long(name: String): Long = longOrNull(name) ?: nullColumn(name)

    public fun longOrNull(name: String): Long?

    public fun longList(name: String): List<Long>

    public fun longSet(name: String): Set<Long> = longList(name).toSet()

    public fun integer(name: String): Int = integerOrNull(name) ?: nullColumn(name)

    public fun integerOrNull(name: String): Int?

    public fun integerList(name: String): List<Int>

    public fun integerSet(name: String): Set<Int> = integerList(name).toSet()

    public fun decimal(name: String): BigDecimal = decimalOrNull(name) ?: nullColumn(name)

    public fun decimalOrNull(name: String): BigDecimal?

    public fun decimalList(name: String): List<BigDecimal>

    public fun decimalSet(name: String): Set<BigDecimal> = decimalList(name).toSet()

    /** A JSON object column, decoded to a map of plain JSON values. */
    public fun map(name: String): Map<String, Any?> = mapOrNull(name) ?: nullColumn(name)

    /** A JSON object column, decoded to a map of plain JSON values. */
    public fun mapOrNull(name: String): Map<String, Any?>?

    /**
     * A JSON array of objects (e.g.m the aggregated rows of a one-to-many group), one nested
     * [RowContext] per element, empty when NULL. A nested row is read with the same accessors and
     * the same rules as this one.
     */
    public fun objectList(name: String): List<RowContext> =
        (objectListOrNull(name) ?: emptyList()).map(::MapRowContext)

    /** The JSON array of objects behind [objectList], decoded to plain maps, `null` when NULL. */
    public fun objectListOrNull(name: String): List<Map<String, Any?>>?

    /**
     * Column [name] exactly as the source produced it, without any conversion. For infrastructure
     * code that handles values of a type unknown at compile time (e.g. a key read back to be bound
     * into the next statement); a [RowMapper] uses the typed accessors.
     */
    public fun rawOrNull(name: String): Any?
}

/** Thrown when a [RowContext] is asked for column [name], which the row does not have. */
public class UnknownColumnException(
    public val name: String,
    public val available: Collection<String>,
) : IllegalArgumentException("Column '$name' is not part of this row (columns: $available)")

private fun nullColumn(name: String): Nothing =
    throw IllegalStateException("Column '$name' is null")
