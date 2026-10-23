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

import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.DATABASE_TIME_MS
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.FAILED_COUNT
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.N_PLUS_ONE
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.QUERY_COUNT
import dev.buijs.stratastax.persistence.observability.api.SpanAttributes.TRANSACTION_COUNT
import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.trace.Span
import kotlin.time.DurationUnit.MILLISECONDS

private val log = KotlinLogging.logger {}

/**
 * Reports a [RequestQueryProfile] when its request ends: sets the totals on the request's span
 * (`stratastax.db.query_count`, `stratastax.db.duration_ms`, `stratastax.db.n_plus_one`, ...) and
 * logs a warning for a probable N+1 problem or a request over its query budget.
 *
 * @param nPlusOneThreshold how often one SQL text may run before it counts as an N+1 problem.
 * @param queryBudget how many data statements a request may run before it is reported.
 */
public class RequestQueryReporter(
    private val nPlusOneThreshold: Int = 3,
    private val queryBudget: Int = 50,
) {

    /** Reports [profile] on [span] for the request known as [requestId], and returns it. */
    public fun report(
        profile: RequestQueryProfile,
        span: Span,
        requestId: String,
    ): RequestQuerySnapshot {
        val snapshot = profile.snapshot()
        val repeated = snapshot.repeatedQueries(nPlusOneThreshold)
        span.setAttribute(QUERY_COUNT, snapshot.queryCount.toLong())
        span.setAttribute(TRANSACTION_COUNT, snapshot.transactionCount.toLong())
        span.setAttribute(FAILED_COUNT, snapshot.failedCount.toLong())
        span.setAttribute(DATABASE_TIME_MS, snapshot.databaseTime.toDouble(MILLISECONDS))
        span.setAttribute(N_PLUS_ONE, repeated.isNotEmpty())

        val trace = span.spanContext.traceId
        if (repeated.isNotEmpty()) {
            log.warn {
                "Potential N+1 query in request $requestId (trace $trace): " +
                    repeated.entries.joinToString("; ") { (sql, count) -> "${count}x $sql" }
            }
        }

        if (snapshot.queryCount > queryBudget) {
            log.warn {
                "Request $requestId (trace $trace) ran ${snapshot.queryCount} queries, " +
                    "over its budget of $queryBudget"
            }
        }

        return snapshot
    }
}
