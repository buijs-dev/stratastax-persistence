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

/**
 * How the playset is searched: per field the literal SQL a condition and a sort use, generated
 * next to [PlaysetSql] (written by hand for this proof of concept). A field of a relation is
 * filtered through an `EXISTS` along the relation chain; only root and one-to-one fields sort.
 */
internal object PlaysetSearchSql {

    private const val GAME = "SELECT 1 FROM game game_x WHERE playset.game_id = game_x.id"
    private const val OWNER = "SELECT 1 FROM app_user owner_x WHERE playset.owner_id = owner_x.id"
    private const val LOADOUT =
        "SELECT 1 FROM loadout loadout_x WHERE playset.loadout_id = loadout_x.id"
    private const val EXPANSIONS =
        "SELECT 1 FROM playset_expansion expansions_xj " +
            "JOIN expansion expansions_x ON expansions_xj.expansion_id = expansions_x.id " +
            "WHERE playset.id = expansions_xj.playset_id"
    private const val NOTES =
        "SELECT 1 FROM playset_note notes_x WHERE playset.id = notes_x.playset_id AND notes_x.deleted_at IS NULL"
    private const val CONDITION = Related.CONDITION

    val sql =
        SearchSql(
            dialect = PostgresDialect,
            select = PlaysetSql.SELECT,
            countSelect = PlaysetSql.COUNT,
            defaultConditions = listOf("playset.deleted_at IS NULL"),
            lockClause = "FOR UPDATE OF playset",
            fields =
                mapOf(
                    "id" to root("playset.public_id", SqlType.UUID, neverNull = true),
                    "name" to root("playset.name", SqlType.VARCHAR, neverNull = true),
                    "createdAt" to
                        root(
                            "playset.created_at",
                            SqlType.TIMESTAMPTZ,
                            neverNull = true,
                        ),
                    "version" to root("playset.version", SqlType.BIGINT, neverNull = true),
                    "attributes" to root("playset.attributes", SqlType.JSONB),
                    "deletedAt" to root("playset.deleted_at", SqlType.TIMESTAMPTZ),
                    "gameId" to oneToOne(GAME, "game_x.public_id", "game.public_id", SqlType.UUID),
                    "gameName" to oneToOne(GAME, "game_x.name", "game.name", SqlType.VARCHAR),
                    "ownerId" to
                        oneToOne(OWNER, "owner_x.public_id", "owner.public_id", SqlType.UUID),
                    "ownerDisplayName" to
                        oneToOne(
                            OWNER,
                            "owner_x.display_name",
                            "owner.display_name",
                            SqlType.VARCHAR,
                        ),
                    "loadoutLabel" to
                        oneToOne(LOADOUT, "loadout_x.label", "loadout.label", SqlType.VARCHAR),
                    "expansionsId" to
                        many(
                            "EXISTS ($EXPANSIONS AND $CONDITION)",
                            "expansions_x.public_id",
                            SqlType.UUID,
                        ),
                    "expansionsName" to
                        many(
                            "EXISTS ($EXPANSIONS AND $CONDITION)",
                            "expansions_x.name",
                            SqlType.VARCHAR,
                        ),
                    "notesId" to
                        many("EXISTS ($NOTES AND $CONDITION)", "notes_x.public_id", SqlType.UUID),
                    "notesText" to
                        many("EXISTS ($NOTES AND $CONDITION)", "notes_x.text", SqlType.VARCHAR),
                    "notesGameName" to
                        many(
                            "EXISTS ($NOTES AND EXISTS (SELECT 1 FROM game notesGame_x " +
                                "WHERE notes_x.game_id = notesGame_x.id AND $CONDITION))",
                            "notesGame_x.name",
                            SqlType.VARCHAR,
                        ),
                    "notesLoadoutLabel" to
                        many(
                            "EXISTS ($NOTES AND EXISTS (SELECT 1 FROM loadout notesLoadout_x " +
                                "WHERE notes_x.loadout_id = notesLoadout_x.id AND $CONDITION))",
                            "notesLoadout_x.label",
                            SqlType.VARCHAR,
                        ),
                    "notesReactionsEmoji" to
                        many(
                            "EXISTS ($NOTES AND EXISTS (SELECT 1 FROM note_reaction " +
                                "notesReactions_x WHERE notes_x.public_id = " +
                                "notesReactions_x.note_id AND $CONDITION))",
                            "notesReactions_x.emoji",
                            SqlType.VARCHAR,
                        ),
                ),
        )

    private fun root(
        column: String,
        type: SqlType,
        neverNull: Boolean = false,
    ) = SearchField(type, Column(column), sortColumn = column, neverNull = neverNull)

    /** A field of a one-to-one relation: filtered through [chain], sorted on the joined [sortColumn]. */
    private fun oneToOne(
        chain: String,
        column: String,
        sortColumn: String,
        type: SqlType,
    ) = SearchField(
        type,
        Related("EXISTS ($chain AND $CONDITION)", column, absent = "NOT EXISTS ($chain)"),
        sortColumn,
    )

    /** A field of a one-to-many or many-to-many relation: filtered only, it has no single value to sort on. */
    private fun many(
        exists: String,
        column: String,
        type: SqlType,
    ) = SearchField(type, Related(exists, column))
}
