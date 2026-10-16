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
package dev.buijs.stratastax.persistence.reactive.app

import dev.buijs.stratastax.persistence.core.api.RenderedQuery
import dev.buijs.stratastax.persistence.reactive.api.SqlRenderer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jooq.Query
import org.jooq.conf.ParamType

private val log = KotlinLogging.logger {}

// TODO this should be configurable through a SPI or something
// I will probably always use postgresql for the foreseeable
// future but keeping this a "port" SPI with default postgresql shows clear
// intent

/**
 * Rewrites jOOQ's JDBC-style `?` placeholders (from [Query.getSQL] with [ParamType.INDEXED]) to
 * Postgres's native `$1, $2, ...` positional parameters.
 */
class PostgresSqlRenderer : SqlRenderer {

    override fun render(query: Query): RenderedQuery {
        var index = 0
        var sql = query.getSQL(ParamType.INDEXED)
        while (sql.contains("?")) {
            index++
            sql = sql.replaceFirst("?", "\$$index")
        }

        log.debug { "Postgres SQL: $sql" }

        return RenderedQuery(sql, query.bindValues)
    }
}
