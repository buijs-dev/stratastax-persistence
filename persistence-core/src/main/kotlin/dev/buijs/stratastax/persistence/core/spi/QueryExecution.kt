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

import kotlin.time.Duration

/** Receives every statement that ran against the database, see [QueryExecution]. */
public interface QueryExecutionListener {

    /**
     * Called once per statement, after it completed or failed. An exception thrown here is logged
     * and otherwise ignored: a listener never changes the outcome of the statement or transaction.
     */
    public suspend fun executed(execution: QueryExecution)
}

/** A statement as sent to the database: SQL text with positional placeholders, and their values. */
public data class RenderedQuery(
    val sql: String,
    val parameters: List<Any?>,
)

/**
 * One statement that ran against the database, reported to every [QueryExecutionListener] for
 * every statement, including transaction control (`BEGIN`, `SAVEPOINT`, `COMMIT`, ...) and
 * statements that failed.
 *
 * @property transactionId correlates the statements of one transaction; `null` for a statement that
 *   ran outside any transaction (autocommit).
 * @property rowCount the number of rows returned or affected; `null` when the statement failed.
 * @property error why the statement failed; `null` when it succeeded.
 * @property batchSize how many parameter sets the statement ran with in one round trip: `1` for a
 *   plain statement, more for a batch, whose [query] holds the first set's parameters and whose
 *   [rowCount] counts the rows of every set.
 */
public data class QueryExecution(
    val query: RenderedQuery,
    val operation: QueryOperation,
    val duration: Duration,
    val rowCount: Int?,
    val transactionId: String? = null,
    val error: Throwable? = null,
    val batchSize: Int = 1,
) {

    /** Whether the statement failed. */
    val failed: Boolean
        get() = error != null
}

/** The kind of statement a [QueryExecution] ran, see [of]. */
public enum class QueryOperation(
    public val isTransactionControl: Boolean = false,
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

    public companion object {

        /**
         * Detects the operation of [sql] from its keywords. Leading comments and parentheses are
         * skipped, and a `WITH` statement is classified by its main statement (`WITH ... INSERT` is
         * an [INSERT]).
         */
        public fun of(sql: String): QueryOperation {
            val words = topLevelWords(sql)
            return when (words.firstOrNull()) {
                "select", "values", "table" -> SELECT
                "with" -> mainStatementOf(words)
                "insert" -> INSERT
                "update" -> UPDATE
                "delete" -> DELETE
                "begin", "start" -> BEGIN
                "commit", "end" -> COMMIT
                "rollback", "abort" -> if ("to" in words) ROLLBACK_TO_SAVEPOINT else ROLLBACK
                "savepoint" -> SAVEPOINT
                "release" -> RELEASE_SAVEPOINT
                else -> UNKNOWN
            }
        }

        private fun mainStatementOf(words: List<String>): QueryOperation =
            when (words.firstOrNull { it in MAIN_STATEMENTS }) {
                "insert" -> INSERT
                "update" -> UPDATE
                "delete" -> DELETE
                "select" -> SELECT
                else -> UNKNOWN
            }

        private val MAIN_STATEMENTS = setOf("select", "insert", "update", "delete")
    }
}

/**
 * The lowercase words of [sql] outside comments, string literals, quoted identifiers, and
 * parentheses, enough to tell what kind of statement it is. For a statement that opens with a
 * parenthesis (`(select ...) union ...`) the first word inside it comes first.
 */
private fun topLevelWords(sql: String): List<String> {
    val words = mutableListOf<String>()
    var firstWord: String? = null
    var opensWithParenthesis: Boolean? = null
    var depth = 0
    var index = 0
    while (index < sql.length) {
        val char = sql[index]
        when {
            sql.startsWith("--", index) -> {
                index = sql.indexOf('\n', index).let { if (it < 0) sql.length else it }
            }
            sql.startsWith("/*", index) -> {
                index = sql.indexOf("*/", index + 2).let { if (it < 0) sql.length else it + 2 }
            }
            char == '\'' || char == '"' -> {
                index = closingQuote(sql, index)
            }
            char == '(' -> {
                if (opensWithParenthesis == null) opensWithParenthesis = true
                depth++
                index++
            }
            char == ')' -> {
                depth--
                index++
            }
            char.isLetter() || char == '_' -> {
                if (opensWithParenthesis == null) opensWithParenthesis = false
                val end = wordEnd(sql, index)
                val word = sql.substring(index, end).lowercase()
                if (firstWord == null) firstWord = word
                if (depth == 0) words += word
                index = end
            }
            else -> {
                index++
            }
        }
    }

    return if (opensWithParenthesis == true) listOfNotNull(firstWord) + words else words
}

private fun closingQuote(
    sql: String,
    start: Int,
): Int {
    val quote = sql[start]
    var index = start + 1
    while (index < sql.length) {
        if (sql[index] == quote) {
            if (index + 1 < sql.length && sql[index + 1] == quote) index += 2 else return index + 1
        } else {
            index++
        }
    }

    return sql.length
}

private fun wordEnd(
    sql: String,
    start: Int,
): Int {
    var index = start
    while (index < sql.length && (sql[index].isLetterOrDigit() || sql[index] == '_')) index++
    return index
}
