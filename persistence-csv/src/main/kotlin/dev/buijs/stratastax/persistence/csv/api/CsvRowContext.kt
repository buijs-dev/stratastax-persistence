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

import dev.buijs.stratastax.persistence.csv.app.normalizeCsvColumnName
import org.apache.commons.csv.CSVRecord
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A [CsvRowContext] backed by a single commons-csv [CSVRecord]. Column names are matched through
 * [normalizeCsvColumnName] against the CSV's own real header names, not by exact string equality. A
 * blank cell (empty string) reads as `null`, same as an absent value. Every parse failure (a
 * missing column, or a value that doesn't fit the requested type) throws [CsvImportException]
 * naming the offending column and raw value.
 */
class CsvRowContext(
    private val record: CSVRecord,
) {

    private val realHeaderNameByNormalizedName: Map<String, String> by lazy {
        record.parser.headerNames.associateBy { normalizeCsvColumnName(it) }
    }

    fun stringOrNull(name: String): String? = raw(name)

    fun integerOrNull(name: String): Int? =
        raw(name)?.let { parse(name, it) { value -> value.toInt() } }

    fun longOrNull(name: String): Long? =
        raw(name)?.let { parse(name, it) { value -> value.toLong() } }

    fun decimalOrNull(name: String): BigDecimal? = raw(name)?.let { parse(name, it, ::BigDecimal) }

    fun booleanOrNull(name: String): Boolean? = raw(name)?.let { parseBoolean(name, it) }

    fun uuidOrNull(name: String): UUID? = raw(name)?.let { parse(name, it, UUID::fromString) }

    fun localDateOrNull(name: String): LocalDate? =
        raw(name)?.let { parse(name, it, LocalDate::parse) }

    fun offsetDateTimeOrNull(name: String): OffsetDateTime? =
        raw(name)?.let { parse(name, it, OffsetDateTime::parse) }

    fun rawOrNull(name: String): String? = raw(name)

    fun string(name: String): String = stringOrNull(name) ?: error("Column '$name' is null")

    fun integer(name: String): Int = integerOrNull(name) ?: error("Column '$name' is null")

    fun long(name: String): Long = longOrNull(name) ?: error("Column '$name' is null")

    fun decimal(name: String): BigDecimal = decimalOrNull(name) ?: error("Column '$name' is null")

    fun boolean(name: String): Boolean = booleanOrNull(name) ?: error("Column '$name' is null")

    fun uuid(name: String): UUID = uuidOrNull(name) ?: error("Column '$name' is null")

    fun localDate(name: String): LocalDate =
        localDateOrNull(name) ?: error("Column '$name' is null")

    fun offsetDateTime(name: String): OffsetDateTime =
        offsetDateTimeOrNull(name) ?: error("Column '$name' is null")

    private fun raw(name: String): String? {
        val realHeaderName =
            realHeaderNameByNormalizedName[normalizeCsvColumnName(name)]
                ?: throw CsvImportException(
                    "Column '$name' is not present in the CSV header " +
                        "(found: ${record.parser.headerNames.sorted()})",
                )

        return record.get(realHeaderName).ifBlank { null }
    }

    private fun <T> parse(
        name: String,
        value: String,
        parser: (String) -> T,
    ): T =
        try {
            parser(value)
        } catch (e: Exception) {
            throw CsvImportException(
                "Column '$name' with value '$value' could not be parsed: ${e.message}",
            )
        }

    private fun parseBoolean(
        name: String,
        value: String,
    ): Boolean =
        when (value.trim().lowercase()) {
            "true",
            "1",
            -> {
                true
            }
            "false",
            "0",
            -> {
                false
            }
            else -> {
                throw CsvImportException(
                    "Column '$name' with value '$value' is not a recognized " +
                        "boolean (expected true/false/1/0)",
                )
            }
        }
}
