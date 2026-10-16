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

import dev.buijs.stratastax.persistence.core.api.MapRowContext
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

interface RowContext {

    fun uuid(name: String): UUID = uuidOrNull(name) ?: error("Column '$name' is null")

    fun uuidOrNull(name: String): UUID?

    fun uuidList(name: String): List<UUID>

    fun uuidSet(name: String): Set<UUID> = uuidList(name).toSet()

    fun offsetDateTime(name: String): OffsetDateTime =
        offsetDateTimeOrNull(name) ?: error("Column '$name' is null")

    fun offsetDateTimeOrNull(name: String): OffsetDateTime?

    fun offsetDateTimeList(name: String): List<OffsetDateTime>

    fun offsetDateTimeSet(name: String): Set<OffsetDateTime> = offsetDateTimeList(name).toSet()

    fun boolean(name: String): Boolean = booleanOrNull(name) ?: error("Column '$name' is null")

    fun booleanOrNull(name: String): Boolean?

    fun booleanList(name: String): List<Boolean>

    fun booleanSet(name: String): Set<Boolean> = booleanList(name).toSet()

    fun string(name: String): String = stringOrNull(name) ?: error("Column '$name' is null")

    fun stringOrNull(name: String): String?

    fun stringList(name: String): List<String>

    fun stringSet(name: String): Set<String> = stringList(name).toSet()

    fun long(name: String): Long = longOrNull(name) ?: error("Column '$name' is null")

    fun longOrNull(name: String): Long?

    fun longList(name: String): List<Long>

    fun longSet(name: String): Set<Long> = longList(name).toSet()

    fun integer(name: String): Int = integerOrNull(name) ?: error("Column '$name' is null")

    fun integerOrNull(name: String): Int?

    fun integerList(name: String): List<Int>

    fun integerSet(name: String): Set<Int> = integerList(name).toSet()

    fun decimal(name: String): BigDecimal = decimalOrNull(name) ?: error("Column '$name' is null")

    fun decimalOrNull(name: String): BigDecimal?

    fun decimalList(name: String): List<BigDecimal>

    fun decimalSet(name: String): Set<BigDecimal> = decimalList(name).toSet()

    fun map(name: String): Map<String, Any?> = mapOrNull(name) ?: error("Column '$name' is null")

    fun mapOrNull(name: String): Map<String, Any?>?

    fun objectList(name: String): List<RowContext> =
        (objectListOrNull(name) ?: emptyList()).map { MapRowContext(it) }

    fun objectListOrNull(name: String): List<Map<String, Any?>>?

    fun rawOrNull(name: String): Any?
}
