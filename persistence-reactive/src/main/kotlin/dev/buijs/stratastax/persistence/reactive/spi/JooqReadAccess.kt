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
package dev.buijs.stratastax.persistence.reactive.spi

import dev.buijs.stratastax.persistence.core.api.ReadAccess
import dev.buijs.stratastax.persistence.core.spi.RowContext
import dev.buijs.stratastax.persistence.core.spi.RowMapper
import dev.buijs.stratastax.persistence.reactive.internal.selectRows
import dev.buijs.stratastax.search.core.api.CursorDirection
import dev.buijs.stratastax.search.core.api.Page
import dev.buijs.stratastax.search.core.api.SearchQuery
import org.jooq.impl.DSL

/**
 * [ReadAccess] over jOOQ: a generated `${Entity}ReadAccess` only supplies [binding] and
 * [rowMapper]. Reads use the projection of [JooqSearchQueryTranslator]; every search reads one row
 * more than its limit to tell whether more rows follow.
 */
public interface JooqReadAccess<ID, T> : ReadAccess<ID, T> {

    public val binding: JooqEntityBinding

    public val rowMapper: RowMapper<T>

    /** The id column's value for [id]; override when [ID] wraps it (e.g. a value class). */
    public fun mapId(id: ID): Any = id as Any

    override suspend fun findAll(search: SearchQuery): Page<T> =
        findPage(search, binding.queryTranslator.translate(search, binding.fieldMapping), rowMapper)

    override suspend fun findById(id: ID): T? {
        val parts =
            binding.queryTranslator.translateById(
                binding.idField,
                mapId(id),
                binding.fieldMapping,
            )
        val query = binding.selectRows(parts).where(parts.condition).limit(1)
        return binding.queryExecutor.executeAndGetFirst(query)?.let(rowMapper::map)
    }

    override suspend fun count(search: SearchQuery): Long {
        val condition =
            binding.queryTranslator.condition(search, binding.fieldMapping) ?: DSL.noCondition()
        return binding.queryExecutor.executeAndGetCount(
            binding.dsl
                .selectCount()
                .from(binding.table)
                .where(condition),
        )
    }
}

/**
 * One page of [search], selecting only [fields] (root fields and one-to-one group fields by name)
 * and mapping the rows with [rowMapper] - for a projection that needs less than the full entity.
 */
public suspend fun <ID, T, P> JooqReadAccess<ID, T>.findAllProjection(
    search: SearchQuery,
    fields: Set<String>,
    rowMapper: RowMapper<P>,
): Page<P> =
    findPage(
        search,
        binding.queryTranslator.translate(search, binding.fieldMapping, fields),
        rowMapper,
    )

/**
 * Like [findAllProjection], but every matching row: the limit and cursor of [search] are ignored.
 * For callers that need the complete set (e.g. "all ids this user owns"); mind the size.
 */
public suspend fun <ID, T, P> JooqReadAccess<ID, T>.findAllProjectionList(
    search: SearchQuery,
    fields: Set<String>,
    rowMapper: RowMapper<P>,
): List<P> {
    val parts = binding.queryTranslator.translate(search, binding.fieldMapping, fields)
    val query = binding.selectRows(parts).where(parts.condition).orderBy(parts.orderBy)
    return binding.queryExecutor.executeAndGetAll(query).map(rowMapper::map)
}

/**
 * [JooqReadAccess.findAll] that also locks the returned root rows (`FOR UPDATE OF <table>`) until
 * the surrounding transaction ends - the building block of a "claim the next N rows" work queue.
 * With [skipLocked] (the default) rows another transaction locked are skipped instead of waited
 * for, so concurrent workers each claim different rows. Returns at most [SearchQuery.limit] rows,
 * without a look-ahead row (that row would be locked too).
 *
 * @throws IllegalStateException outside a transaction of [JooqEntityBinding.transactionalExecutor]:
 *   in autocommit mode the locks are released as soon as the query completes.
 */
public suspend fun <ID, T> JooqReadAccess<ID, T>.findAllForUpdate(
    search: SearchQuery,
    skipLocked: Boolean = true,
): List<T> {
    check(binding.transactionalExecutor.isInTransaction()) {
        "findAllForUpdate must run inside a transaction (see UnitOfWork.transaction): " +
            "outside one the row locks are released as soon as the query completes"
    }

    val parts = binding.queryTranslator.translate(search, binding.fieldMapping)
    val locking =
        binding
            .selectRows(parts)
            .where(parts.pageCondition)
            .orderBy(parts.orderBy)
            .limit(search.limit)
            .forUpdate()
            .of(binding.table)
    val query = if (skipLocked) locking.skipLocked() else locking
    return inSortOrder(search, binding.queryExecutor.executeAndGetAll(query)).map(rowMapper::map)
}

private suspend fun <ID, T, P> JooqReadAccess<ID, T>.findPage(
    search: SearchQuery,
    parts: JooqSearchParts,
    rowMapper: RowMapper<P>,
): Page<P> {
    val query =
        binding
            .selectRows(
                parts,
            ).where(parts.pageCondition)
            .orderBy(parts.orderBy)
            .limit(search.limit + 1)
    val rows = binding.queryExecutor.executeAndGetAll(query)
    val hasMore = rows.size > search.limit
    val page = if (hasMore) rows.take(search.limit) else rows
    return Page(items = inSortOrder(search, page).map(rowMapper::map), hasMore = hasMore)
}

/** A page before a cursor is read in reverse order; this puts it back in the sort order. */
private fun inSortOrder(
    search: SearchQuery,
    rows: List<RowContext>,
): List<RowContext> =
    if (search.cursor?.direction == CursorDirection.BEFORE) {
        rows.asReversed()
    } else {
        rows
    }
