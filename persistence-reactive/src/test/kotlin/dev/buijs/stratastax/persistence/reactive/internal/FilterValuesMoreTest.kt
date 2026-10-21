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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private enum class Color { RED }

/** The remaining conversions of filter values and cursor elements. */
class FilterValuesMoreTest {

    @Test
    fun `converts a number to a float and a date-time to an instant or timestamp`() {
        // given
        val instant = Instant.parse("2026-01-02T03:04:05Z")

        // when
        val float = filterValueFor(FilterValue.NumberValue(BigDecimal("1.5")), Float::class.java)
        val asInstant = filterValueFor(FilterValue.DateTimeValue(instant), Instant::class.java)
        val asTimestamp = filterValueFor(FilterValue.DateTimeValue(instant), Timestamp::class.java)

        // then
        assertThat(float).isEqualTo(1.5f)
        assertThat(asInstant).isEqualTo(instant)
        assertThat(asTimestamp).isEqualTo(Timestamp.from(instant))
    }

    @Test
    fun `rejects values a column of another type cannot hold`() {
        // given
        val number = FilterValue.NumberValue(BigDecimal.ONE)
        val dateTime = FilterValue.DateTimeValue(Instant.EPOCH)
        val enumValue = FilterValue.EnumValue("RED")

        // when
        val thrown1 = catchThrowable { filterValueFor(number, UUID::class.java) }

        val thrown2 = catchThrowable { filterValueFor(dateTime, String::class.java) }

        val thrown3 = catchThrowable { filterValueFor(enumValue, Int::class.java) }

        // then
        assertThat(thrown1).isInstanceOf(SearchException::class.java)
        assertThat(thrown2).isInstanceOf(SearchException::class.java)
        assertThat(thrown3).isInstanceOf(SearchException::class.java)
    }

    @Test
    fun `compares an enum value with a text column as text`() {
        // given
        val value = FilterValue.EnumValue("RED")

        // when
        val converted = filterValueFor(value, String::class.java)

        // then
        assertThat(converted).isEqualTo("RED")
    }

    @Test
    fun `converts boolean, timestamp and enum cursor elements`() {
        // given
        val instant = Instant.parse("2026-01-02T03:04:05Z")

        // when
        val flag =
            cursorValueFor(CursorElement.BooleanValue(true), Boolean::class.javaObjectType, "flag")
        val time =
            cursorValueFor(
                CursorElement.TimestampValue(instant),
                OffsetDateTime::class.java,
                "createdAt",
            )
        val enumValue = cursorValueFor(CursorElement.StringValue("RED"), Color::class.java, "color")

        // then
        assertThat(flag).isEqualTo(true)
        assertThat(time).isEqualTo(instant.atOffset(ZoneOffset.UTC))
        assertThat(enumValue).isEqualTo(Color.RED)
    }
}
