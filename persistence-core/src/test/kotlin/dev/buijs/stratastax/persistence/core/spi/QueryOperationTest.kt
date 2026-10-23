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
package dev.buijs.stratastax.persistence.core.spi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Detecting the kind of statement from its SQL text, see [QueryOperation.of]. */
class QueryOperationTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "select * from playset | SELECT",
            "  SELECT 1 | SELECT",
            "(select 1) union (select 2) | SELECT",
            "values (1), (2) | SELECT",
            "with recent as (select 1) select * from recent | SELECT",
            "with recursive tree as (select 1) select * from tree | SELECT",
            "with gone as (delete from t returning id) insert into log select id from gone | INSERT",
            "with ids as (select 1) update playset set name = 'x' | UPDATE",
            "with ids as (select 1) delete from playset | DELETE",
            "insert into playset values (1) | INSERT",
            "UPDATE playset set name = 'x' | UPDATE",
            "delete from playset | DELETE",
            "-- a comment\\nselect 1 | SELECT",
            "/* hint: update */ select 1 | SELECT",
            "select 'insert' from playset | SELECT",
            "BEGIN | BEGIN",
            "BEGIN ISOLATION LEVEL SERIALIZABLE, READ ONLY | BEGIN",
            "START TRANSACTION | BEGIN",
            "COMMIT | COMMIT",
            "END | COMMIT",
            "ROLLBACK | ROLLBACK",
            "ABORT | ROLLBACK",
            "SAVEPOINT stx_sp_1 | SAVEPOINT",
            "RELEASE SAVEPOINT stx_sp_1 | RELEASE_SAVEPOINT",
            "RELEASE stx_sp_1 | RELEASE_SAVEPOINT",
            "ROLLBACK TO SAVEPOINT stx_sp_1 | ROLLBACK_TO_SAVEPOINT",
            "ROLLBACK TRANSACTION TO stx_sp_1 | ROLLBACK_TO_SAVEPOINT",
            "vacuum playset | UNKNOWN",
            "'' | UNKNOWN",
        ],
    )
    fun `detects the operation from the SQL text`(
        sql: String?,
        expected: QueryOperation,
    ) {
        // given
        val statement = (sql ?: "").replace("\\n", "\n")

        // when
        val operation = QueryOperation.of(statement)

        // then
        assertThat(operation).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0} is transaction control: {1}")
    @CsvSource(
        "SELECT, false",
        "INSERT, false",
        "UPDATE, false",
        "DELETE, false",
        "UNKNOWN, false",
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
        // given
        val subject = operation

        // when
        val isTransactionControl = subject.isTransactionControl

        // then
        assertThat(isTransactionControl).isEqualTo(expected)
    }
}
