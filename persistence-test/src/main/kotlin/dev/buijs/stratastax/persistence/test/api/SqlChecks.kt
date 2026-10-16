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
package dev.buijs.stratastax.persistence.test.api

/**
 * Checks on the SQL text of statements that match on what is referenced - a table, a column, a
 * predicate - rather than on exact formatting, so harmless rendering changes do not break a test.
 */
internal object SqlChecks {

    /** Whether a statement reads from or joins [table]. */
    fun usesTable(
        statements: List<String>,
        table: String,
    ): Boolean {
        val pattern = Regex("(?i)\\b(from|join)\\s+\"?${Regex.escape(table)}\"?\\b")
        return statements.any(pattern::containsMatchIn)
    }

    /** Whether a statement mentions [column]. */
    fun usesColumn(
        statements: List<String>,
        column: String,
    ): Boolean {
        val pattern = Regex("(?i)\\b${Regex.escape(column)}\\b")
        return statements.any(pattern::containsMatchIn)
    }

    /** Whether a statement mentions [column] after a `where` keyword. */
    fun usesPredicate(
        statements: List<String>,
        column: String,
    ): Boolean {
        val pattern = Regex("(?i)\\b${Regex.escape(column)}\\b")
        val where = Regex("(?i)\\bwhere\\b")
        return statements.any { sql ->
            where.find(sql)?.let { pattern.containsMatchIn(sql.substring(it.range.first)) } ?: false
        }
    }

    /** Whether no statement contains [text], ignoring case. */
    fun doesNotContain(
        statements: List<String>,
        text: String,
    ): Boolean = statements.none { it.contains(text, ignoreCase = true) }
}
