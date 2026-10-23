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
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.mutiny.sqlclient.Tuple
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

private enum class Stage { DRAFT, FINAL }

/** Converting parsed filter values and cursor elements to the Java type of the column. */
class FilterValuesTest {

    @Test
    fun `converts a number to the column's number type`() {
        // given
        val value = FilterValue.NumberValue(BigDecimal("42"))

        // when
        val converted =
            listOf(
                Int::class.java,
                Long::class.java,
                Short::class.java,
                Double::class.java,
                BigDecimal::class.java,
            ).map { filterValueFor(value, it) }

        // then
        assertThat(converted).containsExactly(42, 42L, 42.toShort(), 42.0, BigDecimal("42"))
    }

    @Test
    fun `rejects a number that does not fit the column`() {
        // given
        val value = FilterValue.NumberValue(BigDecimal("1.5"))

        // when
        val thrown = catchThrowable { filterValueFor(value, Int::class.javaObjectType) }

        // then
        assertThat(thrown)
            .isInstanceOf(SearchException::class.java)
            .hasMessageContaining("does not fit")
    }

    @Test
    fun `converts a UTC date-time to every date-time column type`() {
        // given
        val instant = Instant.parse("2026-01-02T03:04:05Z")
        val value = FilterValue.DateTimeValue(instant)

        // when
        val offset = filterValueFor(value, OffsetDateTime::class.java)
        val local = filterValueFor(value, LocalDateTime::class.java)
        val date = filterValueFor(value, LocalDate::class.java)

        // then
        assertThat(offset).isEqualTo(instant.atOffset(ZoneOffset.UTC))
        assertThat(local).isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 4, 5))
        assertThat(date).isEqualTo(LocalDate.of(2026, 1, 2))
    }

    @Test
    fun `compares a date with a date-time column as the start of that day`() {
        // given
        val value = FilterValue.DateValue(LocalDate.of(2026, 1, 2))

        // when
        val converted = filterValueFor(value, OffsetDateTime::class.java)

        // then
        assertThat(converted).isEqualTo(OffsetDateTime.of(2026, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC))
    }

    @Test
    fun `converts an enum value by name, and rejects an unknown one`() {
        // given
        val value = FilterValue.EnumValue("FINAL")

        // when
        val converted = filterValueFor(value, Stage::class.java)

        // then
        assertThat(converted).isEqualTo(Stage.FINAL)
        assertThatThrownBy {
            filterValueFor(FilterValue.EnumValue("GONE"), Stage::class.java)
        }.isInstanceOf(SearchException::class.java)
    }

    @Test
    fun `decodes JSON to the value a json or jsonb parameter is bound from`() {
        // given
        val values = listOf("""{"a": 1}""", "[1, 2]", "null", "4").map { FilterValue.JsonValue(it) }

        // when
        val decoded = values.map { filterValueFor(it, Any::class.java) }

        // then
        assertThat(
            decoded,
        ).containsExactly(JsonObject().put("a", 1), JsonArray().add(1).add(2), Tuple.JSON_NULL, 4)
    }

    @Test
    fun `passes strings, booleans, uuids and null through`() {
        // given
        val values =
            listOf(
                FilterValue.StringValue("x"),
                FilterValue.BooleanValue(true),
                FilterValue.NullValue,
            )

        // when
        val converted = values.map { filterValueFor(it, String::class.java) }

        // then
        assertThat(converted).containsExactly("x", true, null)
    }

    @Test
    fun `converts cursor elements to the column type`() {
        // given
        val name = CursorElement.StringValue("Catan")
        val number = CursorElement.NumberValue.of(7)

        // when
        val converted =
            listOf(
                cursorValueFor(name, String::class.java, "name"),
                cursorValueFor(number, Long::class.javaObjectType, "id"),
                cursorValueFor(CursorElement.NullValue, String::class.java, "name"),
            )

        // then
        assertThat(converted).containsExactly("Catan", 7L, null)
    }

    @Test
    fun `a list cannot be a cursor position`() {
        // given
        val element = CursorElement.ListValue(listOf(CursorElement.StringValue("a")))

        // when
        val thrown =
            catchThrowable {
                cursorValueFor(element, String::class.java, "name")
            }

        // then
        assertThat(thrown).isInstanceOf(SearchException::class.java)
    }
}
