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

import kotlin.time.Duration

/** The kind of statement a [QueryExecution] ran, detected from its SQL text by [of]. */
enum class QueryOperation(
    val isTransactionControl: Boolean = false,
) {

    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    BEGIN(isTransactionControl = true),
    COMMIT(isTransactionControl = true),
    ROLLBACK(isTransactionControl = true),
    SAVEPOINT(isTransactionControl = true),
    RELEASE_SAVEPOINT(isTransactionControl = true),
    ROLLBACK_TO_SAVEPOINT(isTransactionControl = true),
    UNKNOWN,
    ;

    companion object {
        fun of(sql: String): QueryOperation {
            val normalized = sql.trimStart().lowercase()
            return when {
                normalized.startsWith("select") || normalized.startsWith("with") -> SELECT
                normalized.startsWith("insert") -> INSERT
                normalized.startsWith("update") -> UPDATE
                normalized.startsWith("delete") -> DELETE
                normalized.startsWith("begin") || normalized.startsWith("start transaction") -> BEGIN
                normalized.startsWith("commit") || normalized.startsWith("end") -> COMMIT
                normalized.startsWith("rollback to") -> ROLLBACK_TO_SAVEPOINT
                normalized.startsWith("rollback") || normalized.startsWith("abort") -> ROLLBACK
                normalized.startsWith("savepoint") -> SAVEPOINT
                normalized.startsWith("release") -> RELEASE_SAVEPOINT
                else -> UNKNOWN
            }
        }
    }
}

/**
 * One statement that ran against the database, reported to every
 * [dev.buijs.stratastax.persistence.core.spi.QueryExecutionListener] for *every* statement,
 * including transaction control (`BEGIN`, `SAVEPOINT`, `COMMIT`, ...) and statements that failed.
 *
 * [transactionId] correlates all statements of one transaction (`null`: the statement ran outside
 * any transaction, in autocommit mode). [error] is set when the statement failed, in which case
 * [rowCount] is `null`.
 */
data class QueryExecution(
    val query: RenderedQuery,
    val operation: QueryOperation,
    val duration: Duration,
    val rowCount: Int?,
    val transactionId: String? = null,
    val error: Throwable? = null,
) {

    val failed: Boolean
        get() = error != null
}

data class RenderedQuery(
    val sql: String,
    val parameters: List<Any?>,
)
