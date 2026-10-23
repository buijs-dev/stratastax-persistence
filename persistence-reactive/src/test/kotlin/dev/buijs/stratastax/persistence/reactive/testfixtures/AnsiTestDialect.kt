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
package dev.buijs.stratastax.persistence.reactive.testfixtures

import dev.buijs.stratastax.entity.core.api.SortDirection
import dev.buijs.stratastax.persistence.core.api.TransactionOptions
import dev.buijs.stratastax.persistence.reactive.spi.PersistenceDialect
import dev.buijs.stratastax.persistence.reactive.spi.SearchSyntax
import dev.buijs.stratastax.persistence.reactive.spi.SqlType
import dev.buijs.stratastax.persistence.reactive.spi.TransactionSql

/**
 * A dialect that is not Postgres, written against the public SPI only, as a dialect in another
 * repository would be: `?` placeholders, no array parameters, no row values, `FETCH FIRST`, and
 * identifiers folded to uppercase.
 */
internal object AnsiTestDialect : PersistenceDialect {
    override val transactions: TransactionSql =
        object : TransactionSql {
            override fun begin(options: TransactionOptions): String = "START TRANSACTION"
        }

    override val search: SearchSyntax =
        object : SearchSyntax {
            override val alwaysTrue: String = "1=1"

            override val alwaysFalse: String = "1=0"

            override fun parameter(
                position: Int,
                type: SqlType,
            ): String = "?"

            override fun like(
                column: String,
                pattern: String,
                ignoreCase: Boolean,
            ): String =
                if (ignoreCase) {
                    "LOWER($column) LIKE LOWER($pattern) ESCAPE '\\'"
                } else {
                    "$column LIKE $pattern ESCAPE '\\'"
                }

            override fun orderBy(
                column: String,
                direction: SortDirection,
            ): String {
                val nullsLast = "CASE WHEN $column IS NULL THEN 1 ELSE 0 END"
                val nullsFirst = "CASE WHEN $column IS NULL THEN 0 ELSE 1 END"
                return when (direction) {
                    SortDirection.ASC -> "$nullsLast, $column ASC"
                    SortDirection.DESC -> "$nullsFirst, $column DESC"
                }
            }

            override fun limit(parameter: String): String = "FETCH FIRST $parameter ROWS ONLY"
        }

    override fun columnKey(name: String): String = name.uppercase()
}
