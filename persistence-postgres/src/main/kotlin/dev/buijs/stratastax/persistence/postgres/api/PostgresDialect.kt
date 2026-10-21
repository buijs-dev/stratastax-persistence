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
package dev.buijs.stratastax.persistence.postgres.api

import dev.buijs.stratastax.entity.core.api.SortDirection
import dev.buijs.stratastax.persistence.core.api.TransactionOptions
import dev.buijs.stratastax.persistence.reactive.spi.PersistenceDialect
import dev.buijs.stratastax.persistence.reactive.spi.SearchSyntax
import dev.buijs.stratastax.persistence.reactive.spi.SqlType
import dev.buijs.stratastax.persistence.reactive.spi.TransactionSql

/**
 * PostgreSQL, through the Vert.x reactive Postgres client.
 *
 * - Statements use Postgres' native `$1, $2, ...` placeholders, each with a `::type` cast.
 * - Identifiers are written unquoted, so Postgres folds them to lowercase, and result columns are
 *   matched case-insensitively.
 * - Several search values of a uuid, text, varchar, integer or bigint column are bound as one array
 *   (`= ANY($1::uuid[])`), so the statement text does not depend on how many there are.
 *
 * PostgreSQL-compatible databases, such as CockroachDB and YugabyteDB, use it as it is.
 */
public object PostgresDialect : PersistenceDialect {
    override val transactions: TransactionSql = PostgresTransactionSql

    override val search: SearchSyntax = PostgresSearchSyntax

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

    private object PostgresSearchSyntax : SearchSyntax {
        private val arrayTypes =
            setOf(SqlType.UUID, SqlType.TEXT, SqlType.VARCHAR, SqlType.INTEGER, SqlType.BIGINT)

        override val rowValueComparison: Boolean = true

        override fun parameter(
            position: Int,
            type: SqlType,
        ): String = $$"$$$position::$${type.sqlName}"

        override fun arrayParameter(
            position: Int,
            type: SqlType,
        ): String? = if (type in arrayTypes) $$"$$$position::$${type.sqlName}[]" else null

        override fun like(
            column: String,
            pattern: String,
            ignoreCase: Boolean,
        ): String = "$column ${if (ignoreCase) "ILIKE" else "LIKE"} $pattern ESCAPE '\\'"

        /** `ASC NULLS LAST` and `DESC NULLS FIRST`: the order Postgres uses by default. */
        override fun orderBy(
            column: String,
            direction: SortDirection,
        ): String =
            when (direction) {
                SortDirection.ASC -> "$column ASC NULLS LAST"
                SortDirection.DESC -> "$column DESC NULLS FIRST"
            }
    }
}
