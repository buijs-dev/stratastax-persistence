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
 * What [CsvRowReader.read] produced.
 *
 * @property imported the mapped rows, in file order.
 * @property skipped the rows that could not be mapped, in file order.
 */
public data class CsvImportResult<T>(
    val imported: List<T>,
    val skipped: List<CsvSkippedRow>,
)

/**
 * A data row [CsvRowReader] skipped.
 *
 * @property record the 1-based number of the data record (the header is record 0). Equal to the
 *   line number unless an earlier cell spans several lines.
 * @property reason why the row could not be mapped.
 */
public data class CsvSkippedRow(
    val record: Long,
    val reason: String,
)
