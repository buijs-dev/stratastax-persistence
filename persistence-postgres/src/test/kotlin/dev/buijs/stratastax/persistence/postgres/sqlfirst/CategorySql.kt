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
package dev.buijs.stratastax.persistence.postgres.sqlfirst

import dev.buijs.stratastax.persistence.postgres.api.PostgresDialect
import dev.buijs.stratastax.persistence.reactive.spi.SearchField
import dev.buijs.stratastax.persistence.reactive.spi.SearchSql
import dev.buijs.stratastax.persistence.reactive.spi.SearchTarget.Column
import dev.buijs.stratastax.persistence.reactive.spi.SearchTarget.Related
import dev.buijs.stratastax.persistence.reactive.spi.SqlType
import org.intellij.lang.annotations.Language

/**
 * A self-referencing entity as literal SQL: a category's `parent` (one-to-one) and `children`
 * (one-to-many) are rows of its own table, so every related occurrence of `category` is aliased by
 * its relation's name and never shadows the row itself.
 */
internal object CategorySql {

    @Language("PostgreSQL")
    const val SELECT: String = """
        SELECT category.id AS id,
               category.name AS name,
               parent.name AS parentName,
               COALESCE((
                   SELECT jsonb_agg(jsonb_build_object('childrenName', children_m.name) ORDER BY children_m.id)
                   FROM category children_m
                   WHERE category.id = children_m.parent_id
               ), '[]'::jsonb) AS children
        FROM category
        LEFT JOIN category parent ON category.parent_id = parent.id"""

    private const val PARENT =
        "SELECT 1 FROM category parent_x WHERE category.parent_id = parent_x.id"

    private const val CHILDREN =
        "SELECT 1 FROM category children_x WHERE category.id = children_x.parent_id"

    val search =
        SearchSql(
            dialect = PostgresDialect,
            select = SELECT,
            countSelect = "SELECT count(*) AS count FROM category",
            fields =
                mapOf(
                    "id" to
                        SearchField(
                            SqlType.BIGINT,
                            Column("category.id"),
                            "category.id",
                            neverNull = true,
                        ),
                    "name" to
                        SearchField(
                            SqlType.VARCHAR,
                            Column("category.name"),
                            "category.name",
                            neverNull = true,
                        ),
                    "parentName" to
                        SearchField(
                            SqlType.VARCHAR,
                            Related(
                                "EXISTS ($PARENT AND ${Related.CONDITION})",
                                "parent_x.name",
                                absent = "NOT EXISTS ($PARENT)",
                            ),
                            sortColumn = "parent.name",
                        ),
                    "childrenName" to
                        SearchField(
                            SqlType.VARCHAR,
                            Related(
                                "EXISTS ($CHILDREN AND ${Related.CONDITION})",
                                "children_x.name",
                            ),
                        ),
                ),
        )
}
