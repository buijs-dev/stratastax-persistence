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
package dev.buijs.stratastax.persistence.csv.api

import dev.buijs.stratastax.persistence.core.spi.RowMapper
import dev.buijs.stratastax.persistence.csv.internal.CsvHeader
import dev.buijs.stratastax.persistence.csv.internal.CsvRowContext
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.Reader

/**
 * Reads an RFC 4180 CSV file with a header row and maps every data row with a [RowMapper] - the
 * same mapper type that maps a database row, so one (generated) mapper can serve both.
 *
 * Each row is read through a [dev.buijs.stratastax.persistence.core.spi.RowContext]: columns are
 * matched by [CsvReadOptions.columnNameNormalizer], an empty cell reads as `null`, and a
 * multi-valued cell holds either a JSON array (`["a","b"]`) or a [CsvReadOptions.listDelimiter]
 * separated list. A JSON object or array of objects in a cell is read with `map`/`objectList`.
 */
public object CsvRowReader {

    /**
     * Maps every data row of [input] with [mapper]. [input] is read completely but not closed.
     *
     * @throws CsvImportException when the header has two columns with the same normalized name,
     *   or, with [CsvReadOptions.failOnInvalidRow], for the first row [mapper] cannot map.
     */
    public fun <T> read(
        input: Reader,
        mapper: RowMapper<T>,
        options: CsvReadOptions = CsvReadOptions.DEFAULT,
    ): CsvImportResult<T> {
        val format =
            CSVFormat.RFC4180
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
        val parser = CSVParser.parse(input, format)
        val header = CsvHeader(parser.headerNames, options.columnNameNormalizer)
        val imported = mutableListOf<T>()
        val skipped = mutableListOf<CsvSkippedRow>()
        parser.forEachIndexed { index, record ->
            val recordNumber = index + 1L
            try {
                imported += mapper.map(CsvRowContext(record, header, options))
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                if (options.failOnInvalidRow) {
                    throw CsvImportException("Record $recordNumber could not be mapped: $reason", e)
                }

                skipped += CsvSkippedRow(recordNumber, reason)
            }
        }

        return CsvImportResult(imported, skipped)
    }
}
