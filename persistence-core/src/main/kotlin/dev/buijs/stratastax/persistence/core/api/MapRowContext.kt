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
package dev.buijs.stratastax.persistence.core.api

import dev.buijs.stratastax.persistence.core.spi.RowContext
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A [RowContext] backed by a plain `Map<String, Any?>`, what [RowContext.objectList] wraps each
 * element in. Parses JSON-decoded values (JSON has no native UUID/date-time type, so those arrive
 * as String; numbers arrive as some Number subtype) using only plain Kotlin/JDK types, no framework
 * dependency.
 */
class MapRowContext(
    private val values: Map<String, Any?>,
) : RowContext {

    override fun uuidOrNull(name: String): UUID? = (values[name] as? String)?.let(UUID::fromString)

    override fun uuidList(name: String): List<UUID> =
        (values[name] as? List<*>)?.map { UUID.fromString(it as String) } ?: emptyList()

    override fun offsetDateTimeOrNull(name: String): OffsetDateTime? =
        (values[name] as? String)?.let(OffsetDateTime::parse)

    override fun offsetDateTimeList(name: String): List<OffsetDateTime> =
        (values[name] as? List<*>)?.map { OffsetDateTime.parse(it as String) } ?: emptyList()

    override fun booleanOrNull(name: String): Boolean? = values[name] as? Boolean

    override fun booleanList(name: String): List<Boolean> =
        (values[name] as? List<*>)?.map { it as Boolean } ?: emptyList()

    override fun stringOrNull(name: String): String? = values[name] as? String

    override fun stringList(name: String): List<String> =
        (values[name] as? List<*>)?.map { it as String } ?: emptyList()

    override fun longOrNull(name: String): Long? = (values[name] as? Number)?.toLong()

    override fun longList(name: String): List<Long> =
        (values[name] as? List<*>)?.map { (it as Number).toLong() } ?: emptyList()

    override fun integerOrNull(name: String): Int? = (values[name] as? Number)?.toInt()

    override fun integerList(name: String): List<Int> =
        (values[name] as? List<*>)?.map { (it as Number).toInt() } ?: emptyList()

    override fun decimalOrNull(name: String): BigDecimal? =
        (values[name] as? Number)?.let { BigDecimal(it.toString()) }

    override fun decimalList(name: String): List<BigDecimal> =
        (values[name] as? List<*>)?.map { BigDecimal(it.toString()) } ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    override fun objectListOrNull(name: String): List<Map<String, Any?>>? =
        values[name] as? List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    override fun mapOrNull(name: String): Map<String, Any?>? = values[name] as? Map<String, Any?>

    override fun rawOrNull(name: String): Any? = values[name]
}
