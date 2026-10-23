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
import io.opentelemetry.context.Context
import kotlin.coroutines.CoroutineContext

/**
 * Ties the statements a request causes to that request: put it in the coroutine context the request
 * runs in (an ambient-context contributor does so for every generated resource) and
 * [OpenTelemetryQueryListener] makes each statement, and each transaction, a child of [parent].
 *
 * Being a [PropagatedContextElement], it survives the reactive boundaries `inheritedUni` crosses,
 * and a transaction's block sees it because the transaction keeps the caller's context.
 *
 * @property requestId the id the request is known by in logs, e.g., its `X-Request-Id` header.
 * @property parent the OpenTelemetry context of the request, holding its server span.
 * @property method the HTTP method, when known.
 * @property route the route template (`/matches/{id}`), when known; never the raw path, which may
 *   carry identifiers.
 */
public class QueryCorrelation(
    public val requestId: String,
    public val parent: Context,
    public val method: String? = null,
    public val route: String? = null,
) : PropagatedContextElement {

    public companion object Key : CoroutineContext.Key<QueryCorrelation>

    override val key: CoroutineContext.Key<QueryCorrelation>
        get() = Key
}
