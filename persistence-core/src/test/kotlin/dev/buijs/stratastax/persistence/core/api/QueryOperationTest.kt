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
package dev.buijs.stratastax.persistence.core.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class QueryOperationTest {

    @ParameterizedTest
    @CsvSource(
        "select * from playset, SELECT",
        "'  with recent as (select 1) select * from recent', SELECT",
        "insert into playset values (1), INSERT",
        "UPDATE playset set name = 'x', UPDATE",
        "delete from playset, DELETE",
        "BEGIN, BEGIN",
        "'BEGIN ISOLATION LEVEL SERIALIZABLE, READ ONLY', BEGIN",
        "COMMIT, COMMIT",
        "ROLLBACK, ROLLBACK",
        "SAVEPOINT stx_sp_1, SAVEPOINT",
        "RELEASE SAVEPOINT stx_sp_1, RELEASE_SAVEPOINT",
        "ROLLBACK TO SAVEPOINT stx_sp_1, ROLLBACK_TO_SAVEPOINT",
        "vacuum playset, UNKNOWN",
    )
    fun `detects the operation from the SQL text`(
        sql: String,
        expected: QueryOperation,
    ) {
        assertThat(QueryOperation.of(sql)).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "SELECT, false",
        "INSERT, false",
        "BEGIN, true",
        "COMMIT, true",
        "ROLLBACK, true",
        "SAVEPOINT, true",
        "RELEASE_SAVEPOINT, true",
        "ROLLBACK_TO_SAVEPOINT, true",
    )
    fun `marks transaction control statements`(
        operation: QueryOperation,
        expected: Boolean,
    ) {
        assertThat(operation.isTransactionControl).isEqualTo(expected)
    }
}
