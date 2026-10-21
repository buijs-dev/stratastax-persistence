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
 * A [RowContext] over a `Map<String, Any?>` of decoded JSON values; what [RowContext.objectList]
 * wraps each element of a JSON array in.
 *
 * JSON has no UUID or date type, so those are read from their ISO string form; numbers can be any
 * [Number]. A value that already has the requested type (e.g., a [UUID]) is accepted as is.
 */
public class MapRowContext(
    private val values: Map<String, Any?>,
) : RowContext {

    override fun uuidOrNull(name: String): UUID? = read(name, ::toUuid)

    override fun uuidList(name: String): List<UUID> = readList(name, ::toUuid)

    override fun offsetDateTimeOrNull(name: String): OffsetDateTime? =
        read(name, ::toOffsetDateTime)

    override fun offsetDateTimeList(name: String): List<OffsetDateTime> =
        readList(name, ::toOffsetDateTime)

    override fun localDateOrNull(name: String): LocalDate? = read(name, ::toLocalDate)

    override fun localDateList(name: String): List<LocalDate> = readList(name, ::toLocalDate)

    override fun booleanOrNull(name: String): Boolean? = read(name, ::toBoolean)

    override fun booleanList(name: String): List<Boolean> = readList(name, ::toBoolean)

    override fun stringOrNull(name: String): String? = read(name, ::toStringValue)

    override fun stringList(name: String): List<String> = readList(name, ::toStringValue)

    override fun longOrNull(name: String): Long? = read(name) { toNumber(it).toLong() }

    override fun longList(name: String): List<Long> = readList(name) { toNumber(it).toLong() }

    override fun integerOrNull(name: String): Int? = read(name) { toNumber(it).toInt() }

    override fun integerList(name: String): List<Int> = readList(name) { toNumber(it).toInt() }

    override fun decimalOrNull(name: String): BigDecimal? = read(name, ::toDecimal)

    override fun decimalList(name: String): List<BigDecimal> = readList(name, ::toDecimal)

    override fun objectListOrNull(name: String): List<Map<String, Any?>>? =
        read(name) { value -> expect<List<*>>(value, "a JSON array").map(::toObject) }

    override fun mapOrNull(name: String): Map<String, Any?>? = read(name, ::toObject)

    override fun rawOrNull(name: String): Any? = read(name) { it }

    private inline fun <T> read(
        name: String,
        convert: (Any) -> T,
    ): T? {
        if (!values.containsKey(name)) throw UnknownColumnException(name, values.keys)
        val value = values[name] ?: return null
        return try {
            convert(value)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Column '$name': ${e.message}", e)
        } catch (e: RuntimeException) {
            throw IllegalArgumentException(
                "Column '$name' with value '$value' is invalid: ${e.message}",
                e,
            )
        }
    }

    private inline fun <T> readList(
        name: String,
        crossinline convert: (Any) -> T,
    ): List<T> =
        read(name) { value ->
            expect<List<*>>(value, "a JSON array").map { element ->
                convert(element ?: throw IllegalArgumentException("contains a null element"))
            }
        } ?: emptyList()
}

private inline fun <reified T> expect(
    value: Any,
    description: String,
): T = value as? T ?: throw IllegalArgumentException("expected $description but was '$value'")

private fun toUuid(value: Any): UUID =
    value as? UUID ?: UUID.fromString(expect<String>(value, "a UUID"))

private fun toOffsetDateTime(value: Any): OffsetDateTime =
    value as? OffsetDateTime ?: OffsetDateTime.parse(expect<String>(value, "a date-time"))

private fun toLocalDate(value: Any): LocalDate =
    value as? LocalDate ?: LocalDate.parse(expect<String>(value, "a date"))

private fun toBoolean(value: Any): Boolean = expect(value, "a boolean")

private fun toStringValue(value: Any): String = expect(value, "a string")

private fun toNumber(value: Any): Number = expect(value, "a number")

private fun toDecimal(value: Any): BigDecimal =
    value as? BigDecimal ?: BigDecimal(toNumber(value).toString())

private fun toObject(value: Any?): Map<String, Any?> {
    val map = expect<Map<*, *>>(value ?: "null", "a JSON object")
    return map.entries.associate { (key, element) ->
        expect<String>(key ?: "null", "a string key") to element
    }
}
