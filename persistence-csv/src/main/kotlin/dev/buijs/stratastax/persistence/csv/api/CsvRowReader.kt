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

import dev.buijs.stratastax.persistence.csv.spi.CsvRowMapper
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.Reader

object CsvRowReader {

    fun <T> read(
        input: Reader,
        mapper: CsvRowMapper<T>,
    ): CsvImportResult<T> {
        val format =
            CSVFormat.RFC4180
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
        return CSVParser.parse(input, format).use { parser ->
            val imported = mutableListOf<T>()
            val skipped = mutableListOf<Int>()
            parser.mapIndexed { index, record ->
                try {
                    imported.add(mapper.map(CsvRowContext(record)))
                } catch (_: RuntimeException) {
                    skipped.add(index)
                }
            }

            CsvImportResult(imported, skipped)
        }
    }
}
