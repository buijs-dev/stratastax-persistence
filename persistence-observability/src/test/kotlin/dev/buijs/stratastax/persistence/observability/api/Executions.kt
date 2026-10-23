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

import dev.buijs.stratastax.persistence.core.spi.QueryExecution
import dev.buijs.stratastax.persistence.core.spi.QueryOperation
import dev.buijs.stratastax.persistence.core.spi.RenderedQuery
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal val T0: Instant = Instant.parse("2026-10-22T10:00:00Z")

/** A statement as the executor reports it, starting [startMillis] after [T0]. */
internal fun execution(
    sql: String,
    startMillis: Long = 0,
    duration: Duration = 1.milliseconds,
    transactionId: String? = null,
    parameters: List<Any?> = emptyList(),
    rowCount: Int? = 1,
    error: Throwable? = null,
    batchSize: Int = 1,
) = QueryExecution(
    query = RenderedQuery(sql, parameters),
    operation = QueryOperation.of(sql),
    duration = duration,
    startedAt = T0.plusMillis(startMillis),
    rowCount = if (error == null) rowCount else null,
    transactionId = transactionId,
    error = error,
    batchSize = batchSize,
)
