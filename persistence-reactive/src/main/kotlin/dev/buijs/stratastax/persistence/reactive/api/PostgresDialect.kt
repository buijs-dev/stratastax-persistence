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
package dev.buijs.stratastax.persistence.reactive.api

import dev.buijs.stratastax.persistence.core.api.TransactionOptions
import dev.buijs.stratastax.persistence.reactive.spi.PersistenceDialect
import dev.buijs.stratastax.persistence.reactive.spi.TransactionSql

/**
 * PostgreSQL, through the Vert.x reactive Postgres client.
 *
 * - Statements use Postgres' native `$1, $2, ...` placeholders.
 * - Identifiers are written unquoted, so Postgres folds them to lowercase, and result columns are
 *   matched case-insensitively.
 */
public object PostgresDialect : PersistenceDialect {

    override val transactions: TransactionSql = PostgresTransactionSql

    override fun columnKey(name: String): String = name.lowercase()

    private object PostgresTransactionSql : TransactionSql {
        override fun begin(options: TransactionOptions): String {
            val modes =
                buildList {
                    options.isolation?.let {
                        add("ISOLATION LEVEL ${it.name.replace('_', ' ')}")
                    }

                    if (options.readOnly) {
                        add("READ ONLY")
                    }
                }

            return if (modes.isEmpty()) {
                "BEGIN"
            } else {
                modes.joinToString(", ", prefix = "BEGIN ")
            }
        }
    }
}
