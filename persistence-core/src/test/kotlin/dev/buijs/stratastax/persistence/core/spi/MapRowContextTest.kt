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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * [MapRowContext] over decoded JSON values, and through it the default accessors [RowContext]
 * derives from the abstract ones (`x(name)`, `xSet(name)`, [RowContext.objectList]).
 */
class MapRowContextTest {

    private val id = UUID.fromString("7f6b1a5e-3a59-4b7e-9a3c-2b1f0c9d8e7a")
    private val at = OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC)
    private val day = LocalDate.of(2026, 1, 2)

    @Nested
    inner class ScalarValues {

        @Test
        fun `reads every type from its JSON representation`() {
            // given
            val row =
                MapRowContext(
                    mapOf(
                        "id" to id.toString(),
                        "at" to at.toString(),
                        "day" to day.toString(),
                        "active" to true,
                        "name" to "Catan",
                        "big" to 9_000_000_000L,
                        "small" to 7,
                        "price" to 12.5,
                    ),
                )

            // when
            val values =
                listOf(
                    row.uuid("id"),
                    row.offsetDateTime("at"),
                    row.localDate("day"),
                    row.boolean("active"),
                    row.string("name"),
                    row.long("big"),
                    row.integer("small"),
                    row.decimal("price"),
                )

            // then
            assertThat(
                values,
            ).containsExactly(id, at, day, true, "Catan", 9_000_000_000L, 7, BigDecimal("12.5"))
        }

        @Test
        fun `accepts values that already have the requested type`() {
            // given
            val row = MapRowContext(mapOf("id" to id, "at" to at, "day" to day))

            // when
            val values = listOf(row.uuid("id"), row.offsetDateTime("at"), row.localDate("day"))

            // then
            assertThat(values).containsExactly(id, at, day)
        }

        @Test
        fun `a null value reads as null and fails the non-null accessor`() {
            // given
            val row = MapRowContext(mapOf("name" to null))

            // when
            val value = row.stringOrNull("name")

            // then
            assertThat(value).isNull()
            assertThatThrownBy { row.string("name") }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("Column 'name' is null")
        }

        @Test
        fun `a column the row does not have throws instead of reading as null`() {
            // given
            val row = MapRowContext(mapOf("name" to "Catan"))

            // when
            val thrown = catchThrowable { row.stringOrNull("title") }

            // then
            assertThat(thrown)
                .isInstanceOfSatisfying(UnknownColumnException::class.java) {
                    assertThat(it.name).isEqualTo("title")
                    assertThat(it.available).containsExactly("name")
                }
        }

        @Test
        fun `a value of the wrong type throws naming the column`() {
            // given
            val row = MapRowContext(mapOf("name" to 42))

            // when
            val thrown = catchThrowable { row.stringOrNull("name") }

            // then
            assertThat(thrown)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Column 'name'")
                .hasMessageContaining("expected a string")
        }

        @Test
        fun `an unparsable value throws naming the column`() {
            // given
            val row = MapRowContext(mapOf("id" to "not-a-uuid"))

            // when
            val thrown = catchThrowable { row.uuidOrNull("id") }

            // then
            assertThat(thrown)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Column 'id'")
        }

        @Test
        fun `rawOrNull returns the value unconverted`() {
            // given
            val row = MapRowContext(mapOf("anything" to listOf(1, "two")))

            // when
            val raw = row.rawOrNull("anything")

            // then
            assertThat(raw).isEqualTo(listOf(1, "two"))
        }
    }

    @Nested
    inner class MultiValueColumns {

        @Test
        fun `reads JSON arrays as lists and sets`() {
            // given
            val row =
                MapRowContext(
                    mapOf(
                        "ids" to listOf(id.toString(), id.toString()),
                        "ats" to listOf(at.toString()),
                        "days" to listOf(day.toString()),
                        "flags" to listOf(true, false),
                        "names" to listOf("a", "b"),
                        "longs" to listOf(1, 2L),
                        "ints" to listOf(3, 4),
                        "prices" to listOf(1.5, 2),
                    ),
                )

            // when
            val values =
                listOf(
                    row.uuidList("ids"),
                    row.uuidSet("ids").toList(),
                    row.offsetDateTimeList("ats"),
                    row.localDateList("days"),
                    row.booleanList("flags"),
                    row.stringSet("names").sorted(),
                    row.longList("longs"),
                    row.integerSet("ints").sorted(),
                    row.decimalList("prices").map { it.toPlainString() },
                )

            // then
            assertThat(values)
                .containsExactly(
                    listOf(id, id),
                    listOf(id),
                    listOf(at),
                    listOf(day),
                    listOf(true, false),
                    listOf("a", "b"),
                    listOf(1L, 2L),
                    listOf(3, 4),
                    listOf("1.5", "2"),
                )
        }

        @Test
        fun `a null array reads as empty`() {
            // given
            val row = MapRowContext(mapOf("names" to null))

            // when
            val names = row.stringList("names")

            // then
            assertThat(names).isEmpty()
        }

        @Test
        fun `an array with a null element throws`() {
            // given
            val row = MapRowContext(mapOf("names" to listOf("a", null)))

            // when
            val thrown = catchThrowable { row.stringList("names") }

            // then
            assertThat(thrown)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("null element")
        }
    }

    @Nested
    inner class JSONObjects {

        @Test
        fun `reads a JSON object as a map`() {
            // given
            val row = MapRowContext(mapOf("attributes" to mapOf("color" to "red", "size" to 3)))

            // when
            val attributes = row.map("attributes")

            // then
            assertThat(attributes).containsEntry("color", "red").containsEntry("size", 3)
        }

        @Test
        fun `reads a JSON array of objects as nested rows`() {
            // given
            val row =
                MapRowContext(
                    mapOf(
                        "expansions" to
                            listOf(
                                mapOf("name" to "Seafarers", "year" to 1997),
                                mapOf("name" to "Cities & Knights", "year" to 1998),
                            ),
                    ),
                )

            // when
            val expansions = row.objectList("expansions")

            // then
            assertThat(expansions.map { it.string("name") })
                .containsExactly("Seafarers", "Cities & Knights")
            assertThat(expansions.map { it.integer("year") }).containsExactly(1997, 1998)
        }

        @Test
        fun `a null array of objects reads as no nested rows`() {
            // given
            val row = MapRowContext(mapOf("expansions" to null))

            // when
            val expansions = row.objectList("expansions")

            // then
            assertThat(expansions).isEmpty()
            assertThat(row.objectListOrNull("expansions")).isNull()
        }

        @Test
        fun `an array element that is not an object throws`() {
            // given
            val row = MapRowContext(mapOf("expansions" to listOf("Seafarers")))

            // when
            val thrown = catchThrowable { row.objectList("expansions") }

            // then
            assertThat(thrown)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("expected a JSON object")
        }
    }
}
