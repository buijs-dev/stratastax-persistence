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
package dev.buijs.stratastax.persistence.observability.api

import dev.buijs.stratastax.ambient.context.api.PropagatedContextElement
import dev.buijs.stratastax.persistence.core.spi.QueryExecution
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * What one request did against the database, at the end of it, see [RequestQueryProfile.snapshot].
 *
 * @property queryCount the data statements (transaction control is not counted, as in the test
 *   harness).
 * @property transactionCount the distinct transactions the statements ran in.
 * @property failedCount the statements, transaction control included, that failed.
 * @property databaseTime the summed duration of every statement, transaction control included: the
 *   time the request spent waiting on the database.
 * @property executionsBySql how often each data statement's SQL text ran.
 * @property truncated whether more distinct SQL texts ran than the profile keeps; [executionsBySql]
 *   then misses some, the counts above do not.
 */
public data class RequestQuerySnapshot(
    val queryCount: Int,
    val transactionCount: Int,
    val failedCount: Int,
    val databaseTime: Duration,
    val executionsBySql: Map<String, Int>,
    val truncated: Boolean,
) {

    /**
     * The SQL texts that ran [threshold] or more times: the signature of an N+1 problem, by the same
     * rule as the test harness' `assertNoNPlusOne`.
     */
    public fun repeatedQueries(threshold: Int): Map<String, Int> =
        executionsBySql.filterValues { it >= threshold }
}

/**
 * Collects what one request does against the database. Put a new one in the request's coroutine
 * context next to its [QueryCorrelation]; [OpenTelemetryQueryListener] records every statement into
 * it and [RequestQueryReporter] reports it when the request ends.
 *
 * Thread-safe. Memory is bounded by [maxDistinctQueries]: generated code runs a small, fixed set of
 * SQL texts, so the bound is only hit by hand-written SQL that inlines values.
 */
public class RequestQueryProfile(
    private val maxDistinctQueries: Int = 200,
) : PropagatedContextElement {

    public companion object Key : CoroutineContext.Key<RequestQueryProfile>

    override val key: CoroutineContext.Key<RequestQueryProfile>
        get() = Key

    private val lock = Any()
    private var queryCount = 0
    private var failedCount = 0
    private var databaseTime = Duration.ZERO
    private var truncated = false
    private val transactions = mutableSetOf<String>()
    private val executionsBySql = mutableMapOf<String, Int>()

    /** Adds [execution] to the profile. */
    public fun record(execution: QueryExecution) {
        synchronized(lock) {
            databaseTime += execution.duration
            if (execution.failed) {
                failedCount++
            }

            execution.transactionId?.let(transactions::add)
            if (execution.operation.isTransactionControl) {
                return
            }

            queryCount++
            val sql = execution.query.sql
            val seen = executionsBySql[sql]
            when {
                seen != null -> executionsBySql[sql] = seen + 1
                executionsBySql.size < maxDistinctQueries -> executionsBySql[sql] = 1
                else -> truncated = true
            }
        }
    }

    /** What was recorded so far. */
    public fun snapshot(): RequestQuerySnapshot =
        synchronized(lock) {
            RequestQuerySnapshot(
                queryCount = queryCount,
                transactionCount = transactions.size,
                failedCount = failedCount,
                databaseTime = databaseTime,
                executionsBySql = executionsBySql.toMap(),
                truncated = truncated,
            )
        }
}
