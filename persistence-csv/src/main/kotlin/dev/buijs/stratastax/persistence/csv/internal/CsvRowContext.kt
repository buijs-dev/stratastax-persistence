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

import dev.buijs.stratastax.persistence.core.spi.MapRowContext
import dev.buijs.stratastax.persistence.core.spi.RowContext
import dev.buijs.stratastax.persistence.csv.api.CsvImportException
import dev.buijs.stratastax.persistence.csv.api.CsvReadOptions
import org.apache.commons.csv.CSVRecord
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A [RowContext] over one [CSVRecord]. A blank cell reads as `null`; values are trimmed before they
 * are parsed. Every failure is a [CsvImportException] naming the column and the raw value.
 *
 * Multi-valued cells hold a JSON array (when the cell starts with `[`) or a
 * [CsvReadOptions.listDelimiter] separated list. JSON objects are decoded to plain maps and read
 * with the same rules as [MapRowContext].
 */
internal class CsvRowContext(
    private val record: CSVRecord,
    private val header: CsvHeader,
    private val options: CsvReadOptions,
) : RowContext {

    override fun uuidOrNull(name: String): UUID? = scalar(name, UUID::fromString)

    override fun uuidList(name: String): List<UUID> = list(name, UUID::fromString)

    override fun offsetDateTimeOrNull(name: String): OffsetDateTime? =
        scalar(name, OffsetDateTime::parse)

    override fun offsetDateTimeList(name: String): List<OffsetDateTime> =
        list(name, OffsetDateTime::parse)

    override fun localDateOrNull(name: String): LocalDate? = scalar(name, LocalDate::parse)

    override fun localDateList(name: String): List<LocalDate> = list(name, LocalDate::parse)

    override fun booleanOrNull(name: String): Boolean? = scalar(name, ::parseBoolean)

    override fun booleanList(name: String): List<Boolean> = list(name, ::parseBoolean)

    override fun stringOrNull(name: String): String? = scalar(name) { it }

    override fun stringList(name: String): List<String> = list(name) { it }

    override fun longOrNull(name: String): Long? = scalar(name, String::toLong)

    override fun longList(name: String): List<Long> = list(name, String::toLong)

    override fun integerOrNull(name: String): Int? = scalar(name, String::toInt)

    override fun integerList(name: String): List<Int> = list(name, String::toInt)

    override fun decimalOrNull(name: String): BigDecimal? = scalar(name, ::BigDecimal)

    override fun decimalList(name: String): List<BigDecimal> = list(name, ::BigDecimal)

    override fun mapOrNull(name: String): Map<String, Any?>? =
        cell(name)?.let { json ->
            parse(name, json) { MapRowContext(mapOf(name to decodeJson(it))).map(name) }
        }

    override fun objectListOrNull(name: String): List<Map<String, Any?>>? =
        cell(name)?.let { json ->
            parse(
                name,
                json,
            ) { MapRowContext(mapOf(name to decodeJson(it))).objectListOrNull(name) }
        }

    override fun rawOrNull(name: String): String? = cell(name)

    /** The trimmed cell of column [name], `null` when blank. */
    private fun cell(name: String): String? {
        val column =
            header.resolve(name)
                ?: throw CsvImportException(
                    "Column '$name' is not present in the CSV header (found: ${header.describe()})",
                )
        return record.get(column).trim().ifEmpty { null }
    }

    private fun <T> scalar(
        name: String,
        parser: (String) -> T,
    ): T? = cell(name)?.let { parse(name, it, parser) }

    private fun <T> list(
        name: String,
        parser: (String) -> T,
    ): List<T> {
        val value = cell(name) ?: return emptyList()
        val elements =
            if (value.startsWith("[")) {
                parse(name, value, ::jsonArrayElements)
            } else {
                value.split(options.listDelimiter).map(String::trim).filter(String::isNotEmpty)
            }

        return elements.map { parse(name, it, parser) }
    }

    private fun <T> parse(
        name: String,
        value: String,
        parser: (String) -> T,
    ): T =
        try {
            parser(value)
        } catch (e: CsvImportException) {
            throw e
        } catch (e: Exception) {
            throw CsvImportException(
                "Column '$name' with value '$value' could not be read: ${e.message}",
                e,
            )
        }

    /** The elements of a JSON array of scalars, as the strings the element parser expects. */
    private fun jsonArrayElements(json: String): List<String> {
        val elements =
            decodeJson(json) as? List<*> ?: throw IllegalArgumentException("not a JSON array")
        return elements.map { element ->
            when (element) {
                null -> throw IllegalArgumentException("the JSON array contains a null element")
                is Map<*, *>, is List<*> -> throw IllegalArgumentException(
                    "the JSON array must hold scalars",
                )
                else -> element.toString()
            }
        }
    }

    private fun parseBoolean(value: String): Boolean =
        when (value.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> throw IllegalArgumentException(
                "not a recognized boolean (expected true/false/1/0)",
            )
        }
}
