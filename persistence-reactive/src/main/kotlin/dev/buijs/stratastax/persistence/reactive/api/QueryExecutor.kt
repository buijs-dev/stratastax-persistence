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
package dev.buijs.stratastax.persistence.reactive.api

import dev.buijs.stratastax.persistence.core.spi.RowContext
import org.jooq.Query

/**
 * Executes a jOOQ [Query] and hands back rows as [RowContext] - the SPI a driver-specific module
 * (e.g. one built on Vert.x's reactive Postgres client) implements, keeping this module itself free
 * of any particular execution framework. jOOQ here is used purely to *build* SQL/`Condition`s; this
 * interface is the only place that SQL actually runs.
 */
interface QueryExecutor {

    suspend fun executeAndGetAll(query: Query): List<RowContext>

    suspend fun executeAndGetFirst(query: Query): RowContext?

    suspend fun exists(query: Query): Boolean

    /**
     * For a scalar `SELECT count(*) ...` [query] - reads the single result column by position, not
     * name, so it doesn't depend on whatever alias jOOQ's `count(*)` expression happens to render.
     */
    suspend fun executeAndGetCount(query: Query): Long

    /** For a plain (non-`RETURNING`) `UPDATE`/`DELETE` - returns the affected row count. */
    suspend fun executeUpdate(query: Query): Int

    /**
     * For an `INSERT`/`UPDATE ... RETURNING *`-shaped [query]. `null` means no row matched (only
     * possible for `UPDATE ... RETURNING` - e.g. a stale `expectedVersion`, or the id not
     * existing - an `INSERT ... RETURNING` always yields exactly one row on success).
     */
    suspend fun executeReturning(query: Query): RowContext?
}
