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
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The accessors [RowContext] derives from the abstract ones: the non-null `x(name)` of every type
 * and the `xSet(name)` of every multivalued type.
 */
class RowContextDefaultsTest {

    private val id = UUID.randomUUID()
    private val at = OffsetDateTime.parse("2026-01-02T03:04:05Z")
    private val day = LocalDate.of(2026, 1, 2)

    private val row =
        MapRowContext(
            mapOf(
                "id" to id,
                "at" to at,
                "day" to day,
                "flag" to false,
                "text" to "t",
                "long" to 1L,
                "int" to 2,
                "decimal" to BigDecimal.TEN,
                "map" to mapOf("k" to "v"),
                "ids" to listOf(id, id),
                "ats" to listOf(at.toString()),
                "days" to listOf(day.toString()),
                "flags" to listOf(true, true),
                "longs" to listOf(1L, 1L),
                "decimals" to listOf(1, 1),
                "absent" to null,
            ),
        )

    @Test
    fun `the non-null accessors return the value`() {
        // given
        val subject = row

        // when
        val values =
            listOf(
                subject.uuid("id"),
                subject.offsetDateTime("at"),
                subject.localDate("day"),
                subject.boolean("flag"),
                subject.string("text"),
                subject.long("long"),
                subject.integer("int"),
                subject.decimal("decimal"),
                subject.map("map"),
            )

        // then
        assertThat(values).containsExactly(
            id,
            at,
            day,
            false,
            "t",
            1L,
            2,
            BigDecimal.TEN,
            mapOf("k" to "v"),
        )
    }

    @Test
    fun `the non-null accessors fail on NULL`() {
        // given
        val readers: List<(RowContext) -> Any> =
            listOf(
                { it.uuid("absent") },
                { it.offsetDateTime("absent") },
                { it.localDate("absent") },
                { it.boolean("absent") },
                { it.long("absent") },
                { it.integer("absent") },
                { it.decimal("absent") },
                { it.map("absent") },
            )

        // when
        val failures = readers.map { read -> catchThrowable { read(row) } }

        // then
        assertThat(failures).allSatisfy { assertThat(it).hasMessage("Column 'absent' is null") }
    }

    @Test
    fun `the set accessors drop duplicates`() {
        // given
        val subject = row

        // when
        val sets =
            listOf(
                subject.uuidSet("ids"),
                subject.offsetDateTimeSet("ats"),
                subject.localDateSet("days"),
                subject.booleanSet("flags"),
                subject.longSet("longs"),
                subject.decimalSet("decimals"),
            )

        // then
        assertThat(sets.map { it.size }).containsOnly(1)
    }
}
