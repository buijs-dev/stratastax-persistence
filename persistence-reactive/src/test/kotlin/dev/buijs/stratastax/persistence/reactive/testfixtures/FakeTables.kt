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
package dev.buijs.stratastax.persistence.reactive.testfixtures

import org.jooq.JSONB
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.jooq.impl.TableImpl
import java.time.LocalDateTime
import java.util.UUID

/**
 * Minimal hand-written stand-ins for what jOOQ codegen would normally produce - this repo has no
 * generated schema yet, so tests build real [TableField]-backed fake tables directly, matching a
 * `MATCH` / `MATCH -> GAME` (1:1) / `MATCH -> MATCH_PARTICIPANT` (1:N) / `MATCH_PARTICIPANT ->
 * GHOST_PLAYER` (1:1, nested inside the 1:N) shape.
 */
internal object MATCH : TableImpl<Record>(DSL.name("match")) {

    // Not nullable - this is the row's own PK, used as "game"'s own join
    // key below; explicit here since every FakeTables column defaults to
    // jOOQ's own nullable()==true otherwise, which would make that join a
    // LEFT JOIN (see JooqSearchQueryTranslator.renderGroups's use of
    // from.dataType.nullable()).
    val ID: TableField<Record, UUID> = createField(DSL.name("id"), SQLDataType.UUID.nullable(false))
    val PUBLIC_ID: TableField<Record, UUID> = createField(DSL.name("public_id"), SQLDataType.UUID)
    val STATUS: TableField<Record, String> = createField(DSL.name("status"), SQLDataType.VARCHAR)
    val PLAYED_AT: TableField<Record, LocalDateTime> =
        createField(DSL.name("played_at"), SQLDataType.LOCALDATETIME)
    val ATTRIBUTES: TableField<Record, JSONB> =
        createField(DSL.name("attributes"), SQLDataType.JSONB)
}

internal object GAME : TableImpl<Record>(DSL.name("game")) {

    val ID: TableField<Record, UUID> = createField(DSL.name("id"), SQLDataType.UUID)
    val PUBLIC_ID: TableField<Record, UUID> = createField(DSL.name("public_id"), SQLDataType.UUID)
    val NAME: TableField<Record, String> = createField(DSL.name("name"), SQLDataType.VARCHAR)
}

// Named like the jOOQ-generated table it stands in for.
@Suppress("ktlint:standard:class-naming")
internal object MATCH_PARTICIPANT : TableImpl<Record>(DSL.name("match_participant")) {

    val ID: TableField<Record, UUID> = createField(DSL.name("id"), SQLDataType.UUID)
    val PUBLIC_ID: TableField<Record, UUID> = createField(DSL.name("public_id"), SQLDataType.UUID)
    val MATCH_ID: TableField<Record, UUID> = createField(DSL.name("match_id"), SQLDataType.UUID)
    val GHOST_PLAYER_ID: TableField<Record, UUID> =
        createField(DSL.name("ghost_player_id"), SQLDataType.UUID)
    val NAME: TableField<Record, String> = createField(DSL.name("name"), SQLDataType.VARCHAR)
    val SCORE: TableField<Record, Int> = createField(DSL.name("score"), SQLDataType.INTEGER)
}

// Named like the jOOQ-generated table it stands in for.
@Suppress("ktlint:standard:class-naming")
internal object GHOST_PLAYER : TableImpl<Record>(DSL.name("ghost_player")) {

    val ID: TableField<Record, UUID> = createField(DSL.name("id"), SQLDataType.UUID)
    val PUBLIC_ID: TableField<Record, UUID> = createField(DSL.name("public_id"), SQLDataType.UUID)
    val DISPLAY_NAME: TableField<Record, String> =
        createField(DSL.name("display_name"), SQLDataType.VARCHAR)
}

/** A many-to-many target table, reached from MATCH via the [MATCH_TAG] junction table. */
internal object TAG : TableImpl<Record>(DSL.name("tag")) {

    val ID: TableField<Record, UUID> = createField(DSL.name("id"), SQLDataType.UUID)
    val PUBLIC_ID: TableField<Record, UUID> = createField(DSL.name("public_id"), SQLDataType.UUID)
    val NAME: TableField<Record, String> = createField(DSL.name("name"), SQLDataType.VARCHAR)
}

/** The junction/link table for the MATCH <-> TAG many-to-many relation. */
// Named like the jOOQ-generated table it stands in for.
@Suppress("ktlint:standard:class-naming")
internal object MATCH_TAG : TableImpl<Record>(DSL.name("match_tag")) {

    val MATCH_ID: TableField<Record, UUID> = createField(DSL.name("match_id"), SQLDataType.UUID)
    val TAG_ID: TableField<Record, UUID> = createField(DSL.name("tag_id"), SQLDataType.UUID)
}
