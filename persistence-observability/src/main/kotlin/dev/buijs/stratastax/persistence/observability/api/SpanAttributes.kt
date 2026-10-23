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

import io.opentelemetry.api.common.AttributeKey

/** The span attributes this module sets: OpenTelemetry's database conventions, and our own. */
internal object SpanAttributes {
    val DB_SYSTEM: AttributeKey<String> =
        AttributeKey.stringKey("db.system.name")
    val DB_OPERATION: AttributeKey<String> =
        AttributeKey.stringKey("db.operation.name")
    val DB_QUERY_TEXT: AttributeKey<String> =
        AttributeKey.stringKey("db.query.text")
    val DB_RETURNED_ROWS: AttributeKey<Long> =
        AttributeKey.longKey("db.response.returned_rows")
    val ERROR_TYPE: AttributeKey<String> =
        AttributeKey.stringKey("error.type")
    val REQUEST_ID: AttributeKey<String> =
        AttributeKey.stringKey("stratastax.request.id")
    val TRANSACTION_ID: AttributeKey<String> =
        AttributeKey.stringKey("stratastax.tx.id")
    val TRANSACTION_OUTCOME: AttributeKey<String> =
        AttributeKey.stringKey("stratastax.tx.outcome")
    val BATCH_SIZE: AttributeKey<Long> =
        AttributeKey.longKey("stratastax.batch.size")
    val QUERY_COUNT: AttributeKey<Long> =
        AttributeKey.longKey("stratastax.db.query_count")
    val TRANSACTION_COUNT: AttributeKey<Long> =
        AttributeKey.longKey("stratastax.db.transaction_count")
    val FAILED_COUNT: AttributeKey<Long> =
        AttributeKey.longKey("stratastax.db.failed_count")
    val DATABASE_TIME_MS: AttributeKey<Double> =
        AttributeKey.doubleKey("stratastax.db.duration_ms")
    val N_PLUS_ONE: AttributeKey<Boolean> =
        AttributeKey.booleanKey("stratastax.db.n_plus_one")

    /** `db.query.parameter.<index>`, the convention's key for a positional bind value. */
    fun parameter(index: Int): AttributeKey<String> =
        AttributeKey.stringKey("db.query.parameter.$index")
}
