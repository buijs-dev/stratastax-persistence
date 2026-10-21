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

import dev.buijs.stratastax.search.core.api.FilterValue
import dev.buijs.stratastax.search.core.api.SearchException
import dev.buijs.stratastax.search.core.spi.CursorElement
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.mutiny.sqlclient.Tuple
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Converts a parsed [FilterValue] to the Java type of the column it is compared with: a number is
 * always a [BigDecimal] and a date-time always a UTC [Instant], while the column may be an `Int`, a
 * `LocalDate`, an enum, and so on.
 *
 * @throws SearchException when the value cannot be represented in [targetType].
 */
internal fun filterValueFor(
    value: FilterValue,
    targetType: Class<*>,
): Any? =
    when (value) {
        is FilterValue.StringValue -> value.value
        is FilterValue.NumberValue -> convertNumber(value.value, targetType)
        is FilterValue.BooleanValue -> value.value
        is FilterValue.DateTimeValue -> convertDateTime(value.value, targetType)
        is FilterValue.DateValue -> convertDate(value.value, targetType)
        is FilterValue.EnumValue -> convertEnum(value.value, targetType)
        is FilterValue.UuidValue -> value.value
        is FilterValue.JsonValue -> jsonValue(value.value)
        is FilterValue.NullValue -> null
    }

/**
 * Converts a cursor [element] to [targetType], the Java type of the column field [fieldName] sorts
 * on, like [filterValueFor] does for a filter. A text element holds a string, a UUID, or an enum
 * name.
 *
 * @throws SearchException for an element that cannot be a cursor position (a list), or that does
 *   not fit [targetType].
 */
internal fun cursorValueFor(
    element: CursorElement,
    targetType: Class<*>,
    fieldName: String,
): Any? =
    when (element) {
        is CursorElement.NullValue -> null
        is CursorElement.StringValue -> textCursorValue(element.value, targetType, fieldName)
        is CursorElement.NumberValue -> convertNumber(element.value, targetType)
        is CursorElement.BooleanValue -> element.value
        is CursorElement.TimestampValue -> convertDateTime(element.value, targetType)
        is CursorElement.ListValue -> {
            throw SearchException("Field '$fieldName' cannot be a cursor position")
        }
    }

private fun textCursorValue(
    value: String,
    targetType: Class<*>,
    fieldName: String,
): Any =
    when {
        targetType == String::class.java -> {
            value
        }
        targetType == UUID::class.java -> {
            runCatching { UUID.fromString(value) }.getOrElse {
                throw SearchException("Cursor value '$value' of field '$fieldName' is not a UUID")
            }
        }
        targetType.isEnum -> {
            convertEnum(value, targetType)
        }
        else -> {
            throw SearchException(
                "Cursor value of field '$fieldName' cannot be read as a ${targetType.simpleName}",
            )
        }
    }

private fun convertNumber(
    value: BigDecimal,
    targetType: Class<*>,
): Any =
    try {
        when (targetType) {
            BigDecimal::class.java -> value
            Int::class.javaObjectType, Int::class.javaPrimitiveType -> value.intValueExact()
            Long::class.javaObjectType, Long::class.javaPrimitiveType -> value.longValueExact()
            Short::class.javaObjectType, Short::class.javaPrimitiveType -> value.shortValueExact()
            Double::class.javaObjectType, Double::class.javaPrimitiveType -> value.toDouble()
            Float::class.javaObjectType, Float::class.javaPrimitiveType -> value.toFloat()
            else -> throw SearchException(
                "A number cannot be compared with a ${targetType.simpleName} column",
            )
        }
    } catch (e: ArithmeticException) {
        throw SearchException("Number $value does not fit a ${targetType.simpleName} column")
    }

/** A date is compared as is with a date column, and as the start of that day (UTC) otherwise. */
private fun convertDate(
    value: LocalDate,
    targetType: Class<*>,
): Any =
    if (targetType == LocalDate::class.java) {
        value
    } else {
        convertDateTime(value.atStartOfDay(ZoneOffset.UTC).toInstant(), targetType)
    }

private fun convertDateTime(
    value: Instant,
    targetType: Class<*>,
): Any =
    when (targetType) {
        Instant::class.java -> value
        OffsetDateTime::class.java -> value.atOffset(ZoneOffset.UTC)
        LocalDateTime::class.java -> LocalDateTime.ofInstant(value, ZoneOffset.UTC)
        LocalDate::class.java -> LocalDate.ofInstant(value, ZoneOffset.UTC)
        Timestamp::class.java -> Timestamp.from(value)
        else -> throw SearchException(
            "A date-time cannot be compared with a ${targetType.simpleName} column",
        )
    }

private fun convertEnum(
    value: String,
    targetType: Class<*>,
): Any =
    when {
        targetType == String::class.java -> {
            value
        }
        targetType.isEnum -> {
            targetType.enumConstants
                .map { it as Enum<*> }
                .firstOrNull { it.name == value }
                ?: throw SearchException("'$value' is not a value of ${targetType.simpleName}")
        }
        else -> {
            throw SearchException(
                "An enum value cannot be compared with a ${targetType.simpleName} column",
            )
        }
    }

/** JSON text as the Vert.x value a json or jsonb parameter is bound from. */
private fun jsonValue(raw: String): Any {
    val text = raw.trim()
    return when {
        text.startsWith("{") -> JsonObject(text)
        text.startsWith("[") -> JsonArray(text)
        text == "null" -> Tuple.JSON_NULL
        else -> Json.decodeValue(text)
    }
}
