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
package dev.buijs.stratastax.persistence.reactive

import dev.buijs.stratastax.persistence.core.api.TransactionIsolation
import dev.buijs.stratastax.persistence.core.api.TransactionOptions
import dev.buijs.stratastax.persistence.reactive.api.toBeginSql
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TransactionOptionsSqlTest {

    @Test
    fun `default options begin a plain transaction`() {
        assertThat(TransactionOptions.DEFAULT.toBeginSql()).isEqualTo("BEGIN")
    }

    @Test
    fun `isolation and read-only become transaction modes`() {
        val options =
            TransactionOptions(isolation = TransactionIsolation.REPEATABLE_READ, readOnly = true)

        assertThat(options.toBeginSql())
            .isEqualTo("BEGIN ISOLATION LEVEL REPEATABLE READ, READ ONLY")
    }

    @Test
    fun `every isolation level renders as Postgres spells it`() {
        assertThat(
            TransactionIsolation.entries.map { TransactionOptions(isolation = it).toBeginSql() },
        ).containsExactly(
            "BEGIN ISOLATION LEVEL READ COMMITTED",
            "BEGIN ISOLATION LEVEL REPEATABLE READ",
            "BEGIN ISOLATION LEVEL SERIALIZABLE",
        )
    }
}
