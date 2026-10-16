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
 * Heuristic, semantic (not exact-string) checks against rendered SQL text - matching on whether a
 * table/column/predicate is *referenced*, not on the query's exact formatting, since asserting on
 * literal SQL text makes tests fragile to harmless rendering changes.
 */
internal object SqlChecks {

    fun usesTable(
        sqlStatements: List<String>,
        table: String,
    ): Boolean {
        val pattern = Regex("(?i)\\b(from|join)\\s+\"?${Regex.escape(table)}\"?\\b")
        return sqlStatements.any(pattern::containsMatchIn)
    }

    fun usesColumn(
        sqlStatements: List<String>,
        column: String,
    ): Boolean {
        val pattern = Regex("(?i)\\b${Regex.escape(column)}\\b")
        return sqlStatements.any(pattern::containsMatchIn)
    }

    /** Like [usesColumn], but only counts a match found after a `where` keyword. */
    fun usesPredicate(
        sqlStatements: List<String>,
        column: String,
    ): Boolean {
        val pattern = Regex("(?i)\\b${Regex.escape(column)}\\b")
        return sqlStatements.any { sql ->
            val whereIndex = sql.indexOf("where", ignoreCase = true)
            whereIndex >= 0 && pattern.containsMatchIn(sql.substring(whereIndex))
        }
    }

    fun doesNotContain(
        sqlStatements: List<String>,
        text: String,
    ): Boolean = sqlStatements.none { it.contains(text, ignoreCase = true) }
}
