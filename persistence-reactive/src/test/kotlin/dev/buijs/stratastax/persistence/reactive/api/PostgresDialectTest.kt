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

import dev.buijs.stratastax.persistence.core.api.TransactionIsolation
import dev.buijs.stratastax.persistence.core.api.TransactionOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** [PostgresDialect]: its DSL settings, placeholders, transaction SQL and identifier rules. */
class PostgresDialectTest {

    @ParameterizedTest(name = "{0}, read-only {1} -> {2}")
    @CsvSource(
        delimiter = '|',
        nullValues = ["-"],
        value = [
            "- | false | BEGIN",
            "SERIALIZABLE | false | BEGIN ISOLATION LEVEL SERIALIZABLE",
            "REPEATABLE_READ | true | BEGIN ISOLATION LEVEL REPEATABLE READ, READ ONLY",
            "- | true | BEGIN READ ONLY",
        ],
    )
    fun `begins a transaction with its isolation level and access mode`(
        isolation: TransactionIsolation?,
        readOnly: Boolean,
        expected: String,
    ) {
        // given
        val options = TransactionOptions(isolation = isolation, readOnly = readOnly)

        // when
        val sql = PostgresDialect.transactions.begin(options)

        // then
        assertThat(sql).isEqualTo(expected)
    }

    @Test
    fun `renders the other transaction-control statements`() {
        // given
        val sql = PostgresDialect.transactions

        // when
        val statements =
            listOf(
                sql.commit(),
                sql.rollback(),
                sql.savepoint("s1"),
                sql.releaseSavepoint("s1"),
                sql.rollbackToSavepoint("s1"),
            )

        // then
        assertThat(statements)
            .containsExactly(
                "COMMIT",
                "ROLLBACK",
                "SAVEPOINT s1",
                "RELEASE SAVEPOINT s1",
                "ROLLBACK TO SAVEPOINT s1",
            )
    }

    @Test
    fun `folds column names to lowercase`() {
        // given
        val names = listOf("createdAt", "User", "players")

        // when
        val keys = names.map(PostgresDialect::columnKey)

        // then
        assertThat(keys).containsExactly("createdat", "user", "players")
    }
}
