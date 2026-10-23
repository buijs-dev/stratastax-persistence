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

import dev.buijs.stratastax.persistence.csv.api.CsvImportException

/**
 * The header row of one CSV file, built once per file: resolves the column name a mapper asks for
 * to the header's own name through [normalize].
 */
internal class CsvHeader(
    private val names: List<String>,
    private val normalize: (String) -> String,
) {

    private val byNormalizedName: Map<String, String> =
        names.groupBy(normalize).mapValues { (normalized, matches) ->
            matches.singleOrNull()
                ?: throw CsvImportException(
                    "CSV header columns $matches all normalize to '$normalized' - rename one of them",
                )
        }

    /** The header's own name for [name], or `null` when the header has no such column. */
    fun resolve(name: String): String? = byNormalizedName[normalize(name)]

    /** The header names, sorted, for error messages. */
    fun describe(): List<String> = names.sorted()
}
