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
package dev.buijs.stratastax.persistence.csv.internal

import dev.buijs.stratastax.persistence.core.spi.RowContext
import dev.buijs.stratastax.persistence.csv.api.CsvImportException
import dev.buijs.stratastax.persistence.csv.api.CsvReadOptions
import dev.buijs.stratastax.persistence.csv.api.normalizeCsvColumnName
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * [CsvRowContext]: reading one CSV record through the [RowContext] accessors - scalars, lists (JSON
 * arrays and delimited cells), JSON objects, and the errors for missing columns and bad values.
 */
class CsvRowContextTest {

    private val format =
        CSVFormat.RFC4180
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .build()

    private fun row(
        header: String,
        data: String,
        options: CsvReadOptions = CsvReadOptions.DEFAULT,
    ): RowContext =
        CSVParser.parse("$header\n$data\n", format).use { parser ->
            CsvRowContext(
                parser.records.single(),
                CsvHeader(parser.headerNames, options.columnNameNormalizer),
                options,
            )
        }

    @Nested
    inner class ScalarCells {

        @Test
        fun `reads every type`() {
            // given
            val id = UUID.randomUUID()
            val row =
                row(
                    "id,name,rank,plays,score,expansion,released,updated_at",
                    "$id,Catan,1,9000000000,9.5,true,2025-01-15,2025-01-15T10:30:00Z",
                )

            // when
            val values =
                listOf(
                    row.uuid("id"),
                    row.string("name"),
                    row.integer("rank"),
                    row.long("plays"),
                    row.decimal("score"),
                    row.boolean("expansion"),
                    row.localDate("released"),
                    row.offsetDateTime("updatedAt"),
                )

            // then
            assertThat(values)
                .containsExactly(
                    id,
                    "Catan",
                    1,
                    9_000_000_000L,
                    BigDecimal("9.5"),
                    true,
                    LocalDate.of(2025, 1, 15),
                    OffsetDateTime.parse("2025-01-15T10:30:00Z"),
                )
        }

        @Test
        fun `trims values before parsing them`() {
            // given
            val row = row("rank,expansion", "\" 3 \",\" TRUE \"")

            // when
            val rank = row.integer("rank")
            val expansion = row.boolean("expansion")

            // then
            assertThat(rank).isEqualTo(3)
            assertThat(expansion).isTrue()
        }

        @Test
        fun `reads the 1-0 convention as booleans`() {
            // given
            val row = row("a,b", "1,0")

            // when
            val values = listOf(row.boolean("a"), row.boolean("b"))

            // then
            assertThat(values).containsExactly(true, false)
        }

        @Test
        fun `a blank cell reads as null`() {
            // given
            val row = row("name,rank", "\"  \",")

            // when
            val name = row.stringOrNull("name")
            val rank = row.integerOrNull("rank")

            // then
            assertThat(name).isNull()
            assertThat(rank).isNull()
            assertThat(row.rawOrNull("name")).isNull()
        }

        @Test
        fun `the non-null accessor fails on a blank cell`() {
            // given
            val row = row("name", "\"\"")

            // when
            val thrown = catchThrowable { row.string("name") }

            // then
            assertThat(thrown)
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("Column 'name' is null")
        }

        @Test
        fun `matches column names through the normalizer`() {
            // given
            val row = row("Year_Published", "1995")

            // when
            val year = row.integer("yearPublished")

            // then
            assertThat(year).isEqualTo(1995)
            assertThat(normalizeCsvColumnName("Year_Published")).isEqualTo("yearpublished")
        }

        @Test
        fun `a column missing from the header throws naming the available columns`() {
            // given
            val row = row("id,name", "1,Catan")

            // when
            val thrown = catchThrowable { row.stringOrNull("title") }

            // then
            assertThat(thrown)
                .isInstanceOf(CsvImportException::class.java)
                .hasMessage("Column 'title' is not present in the CSV header (found: [id, name])")
        }

        @Test
        fun `an unparsable value throws naming the column and the value, keeping the cause`() {
            // given
            val row = row("rank", "first")

            // when
            val thrown = catchThrowable { row.integer("rank") }

            // then
            assertThat(thrown)
                .isInstanceOf(CsvImportException::class.java)
                .hasMessageContaining("Column 'rank' with value 'first'")
                .hasCauseInstanceOf(NumberFormatException::class.java)
        }

        @Test
        fun `an unknown boolean spelling throws`() {
            // given
            val row = row("expansion", "yes")

            // when
            val thrown = catchThrowable { row.boolean("expansion") }

            // then
            assertThat(thrown)
                .isInstanceOf(CsvImportException::class.java)
                .hasMessageContaining("expected true/false/1/0")
        }
    }

    @Nested
    inner class MultiValuedCells {

        @Test
        fun `splits a quoted cell on the list delimiter, trimming and dropping empty elements`() {
            // given
            val row = row("names", "\"Catan, Carcassonne,,Azul \"")

            // when
            val names = row.stringList("names")

            // then
            assertThat(names).containsExactly("Catan", "Carcassonne", "Azul")
        }

        @Test
        fun `uses a custom list delimiter`() {
            // given
            val row = row("ranks", "1;2;3", CsvReadOptions(listDelimiter = ';'))

            // when
            val ranks = row.integerList("ranks")

            // then
            assertThat(ranks).containsExactly(1, 2, 3)
        }

        @Test
        fun `reads a JSON array cell, so elements may contain the delimiter`() {
            // given
            val row = row("names", "\"[\"\"Catan, the game\"\", \"\"Azul\"\"]\"")

            // when
            val names = row.stringList("names")

            // then
            assertThat(names).containsExactly("Catan, the game", "Azul")
        }

        @Test
        fun `reads a JSON array of numbers and booleans`() {
            // given
            val row = row("ranks,flags,prices", "\"[1, 2]\",\"[true, false]\",\"[1.5, 2]\"")

            // when
            val ranks = row.integerList("ranks")
            val flags = row.booleanList("flags")
            val prices = row.decimalList("prices")

            // then
            assertThat(ranks).containsExactly(1, 2)
            assertThat(flags).containsExactly(true, false)
            assertThat(prices).containsExactly(BigDecimal("1.5"), BigDecimal("2"))
        }

        @Test
        fun `reads lists of every scalar type`() {
            // given
            val id = UUID.randomUUID()
            val row =
                row(
                    "ids,days,ats,plays",
                    "$id,\"2025-01-15,2025-01-16\",2025-01-15T10:30:00Z,\"1,2\"",
                )

            // when
            val ids = row.uuidSet("ids")
            val days = row.localDateList("days")
            val ats = row.offsetDateTimeList("ats")
            val plays = row.longList("plays")

            // then
            assertThat(ids).containsExactly(id)
            assertThat(days).containsExactly(LocalDate.of(2025, 1, 15), LocalDate.of(2025, 1, 16))
            assertThat(ats).containsExactly(OffsetDateTime.parse("2025-01-15T10:30:00Z"))
            assertThat(plays).containsExactly(1L, 2L)
        }

        @Test
        fun `a blank cell reads as an empty list`() {
            // given
            val row = row("names,other", ",x")

            // when
            val names = row.stringList("names")

            // then
            assertThat(names).isEmpty()
        }

        @Test
        fun `an invalid element throws naming the column`() {
            // given
            val row = row("ranks", "\"1,two\"")

            // when
            val thrown = catchThrowable { row.integerList("ranks") }

            // then
            assertThat(thrown)
                .isInstanceOf(CsvImportException::class.java)
                .hasMessageContaining("Column 'ranks' with value 'two'")
        }

        @Test
        fun `a JSON array with nested values throws`() {
            // given
            val row = row("names", "\"[[\"\"a\"\"]]\"")

            // when
            val thrown = catchThrowable { row.stringList("names") }

            // then
            assertThat(thrown)
                .isInstanceOf(CsvImportException::class.java)
                .hasMessageContaining("must hold scalars")
        }
    }

    @Nested
    inner class JSONCells {

        @Test
        fun `reads a JSON object cell as a map`() {
            // given
            val row = row("attributes", "\"{\"\"color\"\": \"\"red\"\", \"\"size\"\": 3}\"")

            // when
            val attributes = row.map("attributes")

            // then
            assertThat(attributes).containsEntry("color", "red").containsEntry("size", 3)
        }

        @Test
        fun `reads a JSON array of objects as nested rows`() {
            // given
            val row =
                row(
                    "expansions",
                    "\"[{\"\"name\"\": \"\"Seafarers\"\", \"\"year\"\": 1997}, {\"\"name\"\": \"\"Cities\"\", \"\"year\"\": 1998}]\"",
                )

            // when
            val expansions = row.objectList("expansions")

            // then
            assertThat(expansions.map { it.string("name") }).containsExactly("Seafarers", "Cities")
            assertThat(expansions.map { it.integer("year") }).containsExactly(1997, 1998)
        }

        @Test
        fun `a blank JSON cell reads as null`() {
            // given
            val row = row("attributes,other", ",x")

            // when
            val attributes = row.mapOrNull("attributes")
            val nested = row.objectListOrNull("attributes")

            // then
            assertThat(attributes).isNull()
            assertThat(nested).isNull()
        }

        @Test
        fun `invalid JSON throws naming the column`() {
            // given
            val row = row("attributes", "\"{not json\"")

            // when
            val thrown = catchThrowable { row.map("attributes") }

            // then
            assertThat(thrown)
                .isInstanceOf(CsvImportException::class.java)
                .hasMessageContaining("Column 'attributes'")
        }

        @Test
        fun `a JSON array where an object is expected throws`() {
            // given
            val row = row("attributes", "\"[1]\"")

            // when
            val thrown = catchThrowable { row.map("attributes") }

            // then
            assertThat(thrown)
                .isInstanceOf(CsvImportException::class.java)
                .hasMessageContaining("expected a JSON object")
        }
    }
}
