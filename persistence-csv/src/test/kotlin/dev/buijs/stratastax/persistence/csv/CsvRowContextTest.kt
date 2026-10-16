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
package dev.buijs.stratastax.persistence.csv

import dev.buijs.stratastax.persistence.csv.api.CsvImportException
import dev.buijs.stratastax.persistence.csv.api.CsvRowContext
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class CsvRowContextTest {

    private val header = "id,name,rank,score,isExpansion,released,updatedAt,note"
    private val format =
        CSVFormat.RFC4180
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .build()

    private fun rowContextFor(dataLine: String): CsvRowContext {
        val text = "$header\n$dataLine\n"
        val record = CSVParser.parse(text, format).use { it.records.single() }

        return CsvRowContext(record)
    }

    @Test
    fun `reads every typed accessor from a real CSVRecord`() {
        val id = UUID.randomUUID()
        val row = rowContextFor("$id,Catan,1,9.5,true,2025-01-15,2025-01-15T10:30:00Z,a note")

        assertThat(row.uuid("id")).isEqualTo(id)
        assertThat(row.string("name")).isEqualTo("Catan")
        assertThat(row.integer("rank")).isEqualTo(1)
        assertThat(row.decimal("score")).isEqualTo(BigDecimal("9.5"))
        assertThat(row.boolean("isExpansion")).isEqualTo(true)
        assertThat(row.localDate("released")).isEqualTo(LocalDate.of(2025, 1, 15))
        assertThat(row.offsetDateTime("updatedAt"))
            .isEqualTo(OffsetDateTime.parse("2025-01-15T10:30:00Z"))
        assertThat(row.rawOrNull("note")).isEqualTo("a note")
    }

    @Test
    fun `boolean accepts the 1-0 convention alongside true-false`() {
        val zeroOne =
            rowContextFor("${UUID.randomUUID()},Catan,1,9.5,1,2025-01-15,2025-01-15T10:30:00Z,")
        assertThat(zeroOne.boolean("isExpansion")).isEqualTo(true)

        val falsy =
            rowContextFor("${UUID.randomUUID()},Catan,1,9.5,0,2025-01-15,2025-01-15T10:30:00Z,")
        assertThat(falsy.boolean("isExpansion")).isEqualTo(false)
    }

    @Test
    fun `an unrecognized boolean value throws CsvImportException`() {
        val row =
            rowContextFor("${UUID.randomUUID()},Catan,1,9.5,maybe,2025-01-15,2025-01-15T10:30:00Z,")

        assertThatThrownBy { row.boolean("isExpansion") }
            .isInstanceOf(CsvImportException::class.java)
    }

    @Test
    fun `a blank cell reads as null`() {
        val row =
            rowContextFor("${UUID.randomUUID()},Catan,1,9.5,true,2025-01-15,2025-01-15T10:30:00Z,")

        assertThat(row.stringOrNull("note")).isNull()
    }

    @Test
    fun `a missing column throws CsvImportException`() {
        val row =
            rowContextFor("${UUID.randomUUID()},Catan,1,9.5,true,2025-01-15,2025-01-15T10:30:00Z,")

        assertThatThrownBy { row.string("doesNotExist") }
            .isInstanceOf(CsvImportException::class.java)
    }

    @Test
    fun `an unparsable value throws CsvImportException naming the column`() {
        val row =
            rowContextFor(
                "${UUID.randomUUID()},Catan,not-a-number,9.5,true,2025-01-15,2025-01-15T10:30:00Z,",
            )

        val exception = catchThrowableOfType(CsvImportException::class.java) { row.integer("rank") }

        assert(exception.message!!.contains("rank")) {
            "expected exception message to name the column 'rank', was: ${exception.message}"
        }
    }
}
