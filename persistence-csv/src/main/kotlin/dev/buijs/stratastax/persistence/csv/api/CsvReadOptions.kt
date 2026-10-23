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

/**
 * How [CsvRowReader] reads a CSV file.
 *
 * @property listDelimiter separates the elements of a multivalued cell that is not a JSON array,
 *   e.g. `"Catan,Carcassonne"` (quoted, so the comma does not end the cell). Elements are trimmed
 *   and empty elements are dropped.
 * @property columnNameNormalizer maps both the CSV header names and the names a mapper asks for to
 *   a comparable form, so `is_expansion`, `isExpansion` and `IsExpansion` all match. Two header
 *   names that normalize to the same form are rejected.
 * @property failOnInvalidRow `false` (the default) skips a row the mapper cannot map and reports it
 *   in [CsvImportResult.skipped]; `true` throws [CsvImportException] for the first such row.
 */
public data class CsvReadOptions(
    val listDelimiter: Char = ',',
    val columnNameNormalizer: (String) -> String = ::normalizeCsvColumnName,
    val failOnInvalidRow: Boolean = false,
) {

    public companion object {

        /** Comma-separated lists, case- and underscore-insensitive column names, skip bad rows. */
        public val DEFAULT: CsvReadOptions = CsvReadOptions()
    }
}

/** The default [CsvReadOptions.columnNameNormalizer]: lowercase, without underscores. */
public fun normalizeCsvColumnName(name: String): String = name.trim().lowercase().replace("_", "")
