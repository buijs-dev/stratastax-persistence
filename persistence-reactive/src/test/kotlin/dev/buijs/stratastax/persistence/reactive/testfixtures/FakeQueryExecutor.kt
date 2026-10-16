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
package dev.buijs.stratastax.persistence.reactive.testfixtures

import dev.buijs.stratastax.persistence.core.spi.RowContext
import dev.buijs.stratastax.persistence.reactive.api.QueryExecutor
import org.jooq.Query

/**
 * A no-database [QueryExecutor] test double - the `Query` objects passed in are only ever rendered
 * to SQL text by the production code under test (jOOQ can build/render SQL with no live connection
 * at all), never actually executed; this fake just hands back canned rows/counts and records the
 * last query it saw so tests can assert on the rendered SQL.
 */
internal class FakeQueryExecutor(
    private val rows: List<RowContext> = emptyList(),
    private val returningRow: RowContext? = rows.firstOrNull(),
    private val updateRowCount: Int = 1,
    private val existsResult: (Query) -> Boolean = { rows.isNotEmpty() },
    // Distinct from [rows] only for a `JunctionTargetResolve` lookup's own
    // `executeAndGetAll` call - `null` (the default) means "same as [rows]",
    // preserving every existing fixture's behavior unchanged; set only by a
    // test that needs the resolve lookup's own rows to differ from the
    // create/update's own re-select rows.
    private val resolveRows: List<RowContext>? = null,
    // One entry per successive `executeReturning` call, in order (e.g. the
    // root row's own INSERT/UPDATE ... RETURNING, then a one-to-many child
    // element's own) - lets a test make an *earlier* `executeReturning` call
    // succeed while a *later* one collapses to zero rows (`null`), which
    // [returningRow] alone (one static value for every call) can't express.
    // `null` (the default) means "always [returningRow]", preserving every
    // existing fixture's behavior unchanged. Once exhausted, falls back to
    // the sequence's own last entry.
    private val returningRows: List<RowContext?>? = null,
    // One entry per successive `executeUpdate` call, in order - mirrors
    // [returningRows], for a test whose write is now a single batch
    // INSERT/UPDATE covering several rows at once (see
    // `JooqCreateAccess.writeOneToManyElements`/[BatchWriteSupport]):
    // [updateRowCount] alone (one static value for every call) can't express
    // "this batch call affected N rows, that one affected M". `null` (the
    // default) means "always [updateRowCount]". Once exhausted, falls back
    // to the sequence's own last entry.
    private val updateRowCounts: List<Int>? = null,
    // One entry per successive `executeAndGetAll` call, in order - mirrors
    // [returningRows]/[updateRowCounts], for a test where more than one
    // distinct `SELECT` now goes through this same method (e.g. a one-to-many
    // group's own "current child ids" diff SELECT *and* a nested identity
    // group's own batched FK-resolve SELECT - see
    // [resolveElementFkValuesBatch]): [resolveRows] alone (one static answer
    // for every call) can't tell those two apart. `null` (the default) means
    // "always [resolveRows] ?: [rows]", preserving every existing fixture's
    // behavior unchanged. Once exhausted, falls back to the sequence's own
    // last entry.
    private val allRowsSequence: List<List<RowContext>>? = null,
    private val countResult: Long = 0,
    // One entry per successive `executeAndGetCount` call, in order - mirrors
    // [updateRowCounts]. `null` (the default) means "always [countResult]".
    // Once exhausted, falls back to the sequence's own last entry.
    private val countResults: List<Long>? = null,
) : QueryExecutor {

    private val _allQueries = mutableListOf<Query>()
    private var returningCallCount = 0
    private var updateCallCount = 0
    private var allRowsCallCount = 0
    private var countCallCount = 0

    val allQueries: List<Query>
        get() = _allQueries

    val lastQuery: Query?
        get() = _allQueries.lastOrNull()

    override suspend fun executeAndGetAll(query: Query): List<RowContext> {
        _allQueries += query
        val sequence = allRowsSequence ?: return resolveRows ?: rows
        val index = minOf(allRowsCallCount, sequence.lastIndex)
        allRowsCallCount++
        return sequence[index]
    }

    override suspend fun executeAndGetFirst(query: Query): RowContext? {
        _allQueries += query
        return rows.firstOrNull()
    }

    override suspend fun exists(query: Query): Boolean {
        _allQueries += query
        return existsResult(query)
    }

    override suspend fun executeUpdate(query: Query): Int {
        _allQueries += query
        val sequence = updateRowCounts ?: return updateRowCount
        val index = minOf(updateCallCount, sequence.lastIndex)
        updateCallCount++
        return sequence[index]
    }

    override suspend fun executeReturning(query: Query): RowContext? {
        _allQueries += query
        val sequence = returningRows ?: return returningRow
        val index = minOf(returningCallCount, sequence.lastIndex)
        returningCallCount++
        return sequence[index]
    }

    override suspend fun executeAndGetCount(query: Query): Long {
        _allQueries += query
        val sequence = countResults ?: return countResult
        val index = minOf(countCallCount, sequence.lastIndex)
        countCallCount++
        return sequence[index]
    }
}
