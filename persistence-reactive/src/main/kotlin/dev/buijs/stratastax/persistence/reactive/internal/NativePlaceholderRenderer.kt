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
package dev.buijs.stratastax.persistence.reactive.internal

import dev.buijs.stratastax.persistence.core.spi.RenderedQuery
import dev.buijs.stratastax.persistence.reactive.spi.SqlRenderer
import org.jooq.Query
import org.jooq.SQLDialect
import org.jooq.conf.ParamType
import org.jooq.conf.SettingsTools
import org.jooq.impl.DSL

/**
 * Lets jOOQ itself render the bind values of a query as numbered placeholders (`$1, $2, ...` for
 * [prefix] `$`), in the query's own dialect and settings - or [detachedDialect] for a query built
 * without a `DSLContext`. jOOQ knows which `?` characters are placeholders, so literals and
 * operators containing `?` are never touched.
 */
internal class NativePlaceholderRenderer(
    private val prefix: String,
    private val detachedDialect: SQLDialect,
) : SqlRenderer {

    override fun render(query: Query): RenderedQuery {
        val configuration = query.configuration() ?: DSL.using(detachedDialect).configuration()
        val settings =
            SettingsTools
                .clone(configuration.settings())
                .withParamType(ParamType.NAMED)
                .withRenderNamedParamPrefix(prefix)
        val dsl = DSL.using(configuration.derive(settings))
        return RenderedQuery(dsl.renderNamedParams(query), dsl.extractBindValues(query))
    }
}
