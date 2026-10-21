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
package dev.buijs.stratastax.persistence.reactive.sqlfirst

import org.intellij.lang.annotations.Language

/**
 * Every statement of the playset, as literal SQL: what stratastax-codegen generates from the
 * entity's annotations, written by hand for this proof of concept (see `docs/sql-first-design.md`).
 * This is exactly the text Postgres receives; parameters are numbered placeholders with explicit
 * casts, listed in each statement's kdoc.
 *
 * Configuration: versioned (`version`), soft-deleted (`deleted_at`), an owned `loadout` per playset
 * and per note, `expansions` through a link table, `notes` soft-deleted and synchronized by id,
 * each with nested `reactions`.
 */
internal object PlaysetSql {

    /** The columns of the read projection, each under the name of the field its row mapper reads. */
    const val COLUMNS: String = """
        playset.public_id AS id,
        playset.name AS name,
        playset.created_at AS createdAt,
        playset.version AS version,
        playset.attributes AS attributes,
        game.public_id AS gameId,
        game.name AS gameName,
        owner.public_id AS ownerId,
        owner.display_name AS ownerDisplayName,
        loadout.label AS loadoutLabel,
        COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'expansionsId', expansions_m.public_id,
                'expansionsName', expansions_m.name
            ) ORDER BY expansions_m.id)
            FROM playset_expansion expansions_mj
            JOIN expansion expansions_m ON expansions_mj.expansion_id = expansions_m.id
            WHERE playset.id = expansions_mj.playset_id
        ), '[]'::jsonb) AS expansions,
        COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'notesId', notes_m.public_id,
                'notesText', notes_m.text,
                'notesGameName', notesGame_m.name,
                'notesLoadoutLabel', notesLoadout_m.label,
                'notesReactions', COALESCE((
                    SELECT jsonb_agg(jsonb_build_object(
                        'notesReactionsEmoji', notesReactions_m.emoji
                    ) ORDER BY notesReactions_m.id)
                    FROM note_reaction notesReactions_m
                    WHERE notes_m.public_id = notesReactions_m.note_id
                ), '[]'::jsonb)
            ) ORDER BY notes_m.id)
            FROM playset_note notes_m
            LEFT JOIN game notesGame_m ON notes_m.game_id = notesGame_m.id
            LEFT JOIN loadout notesLoadout_m ON notes_m.loadout_id = notesLoadout_m.id
            WHERE playset.id = notes_m.playset_id AND notes_m.deleted_at IS NULL
        ), '[]'::jsonb) AS notes"""

    /** The table and its one-to-one relations, each joined under the relation's name. */
    const val FROM: String = """
        playset
        LEFT JOIN game ON playset.game_id = game.id
        JOIN app_user owner ON playset.owner_id = owner.id
        LEFT JOIN loadout ON playset.loadout_id = loadout.id"""

    /** The read projection; `findAll` adds the search's conditions, sort and limit. */
    @Language("PostgreSQL")
    const val SELECT: String = "SELECT $COLUMNS\nFROM $FROM"

    /** `$1` public id. */
    @Language("PostgreSQL")
    const val FIND_BY_ID: String =
        "$SELECT\nWHERE playset.public_id = $1::uuid AND playset.deleted_at IS NULL\nLIMIT 1"

    /** The rows a write reads back. `$1` public ids. */
    @Language("PostgreSQL")
    const val SELECT_BY_IDS: String =
        "$SELECT\nWHERE playset.public_id = ANY($1::uuid[]) AND playset.deleted_at IS NULL"

    @Language("PostgreSQL")
    const val COUNT: String = "SELECT count(*) AS count FROM playset"

    /** The `name` projection; the joins stay for a sort on a relation. */
    @Language("PostgreSQL")
    const val SELECT_NAMES: String = "SELECT playset.name AS name\nFROM $FROM"

    // Create

    /** An owned loadout, of a playset or of a note. `$1` label. */
    @Language("PostgreSQL")
    const val INSERT_LOADOUT: String =
        "INSERT INTO loadout (label) VALUES ($1::varchar) RETURNING id"

    /**
     * A playset, resolving its game and owner by public id - and inserting nothing when one of them
     * does not exist. `$1` public id, `$2` name, `$3` attributes, `$4` loadout id, `$5` game public
     * id, `$6` owner public id.
     */
    @Language("PostgreSQL")
    const val INSERT: String = """
        INSERT INTO playset (public_id, name, attributes, loadout_id, version, game_id, owner_id)
        SELECT $1::uuid, $2::varchar, $3::jsonb, $4::bigint, 1,
               (SELECT id FROM game WHERE public_id = $5::uuid),
               (SELECT id FROM app_user WHERE public_id = $6::uuid)
        WHERE ($5::uuid IS NULL OR EXISTS (SELECT 1 FROM game WHERE public_id = $5::uuid))
          AND EXISTS (SELECT 1 FROM app_user WHERE public_id = $6::uuid)
        RETURNING id, public_id"""

    /** The links of a playset to the expansions it does not link yet. `$1` playset id, `$2` expansion public ids. */
    @Language("PostgreSQL")
    const val INSERT_EXPANSIONS: String = """
        INSERT INTO playset_expansion (playset_id, expansion_id)
        SELECT $1::bigint, expansion.id
        FROM expansion
        WHERE expansion.public_id = ANY($2::uuid[])
          AND NOT EXISTS (
              SELECT 1 FROM playset_expansion link
              WHERE link.playset_id = $1::bigint AND link.expansion_id = expansion.id
          )"""

    /**
     * A note, resolving its game by public id. `$1` public id, `$2` text, `$3` playset id, `$4`
     * loadout id, `$5` game public id.
     */
    @Language("PostgreSQL")
    const val INSERT_NOTE: String = """
        INSERT INTO playset_note (public_id, text, playset_id, loadout_id, version, game_id)
        SELECT $1::uuid, $2::varchar, $3::bigint, $4::bigint, 1,
               (SELECT id FROM game WHERE public_id = $5::uuid)
        WHERE $5::uuid IS NULL OR EXISTS (SELECT 1 FROM game WHERE public_id = $5::uuid)
        RETURNING public_id"""

    /** `$1` public id, `$2` emoji, `$3` note public id. */
    @Language("PostgreSQL")
    const val INSERT_REACTION: String =
        "INSERT INTO note_reaction (public_id, emoji, note_id) VALUES ($1::uuid, $2::varchar, $3::uuid)"

    // Update

    /**
     * A patch: a column whose flag is false keeps its value. Matches nothing when the row is gone,
     * its version moved on, or the patched game does not exist. `$1` public id, `$2` expected
     * version, `$3`/`$4` name, `$5`/`$6` attributes, `$7`/`$8` game public id.
     */
    @Language("PostgreSQL")
    const val UPDATE: String = """
        UPDATE playset
        SET name = CASE WHEN $3::boolean THEN $4::varchar ELSE name END,
            attributes = CASE WHEN $5::boolean THEN $6::jsonb ELSE attributes END,
            game_id = CASE WHEN $7::boolean THEN (SELECT id FROM game WHERE public_id = $8::uuid) ELSE game_id END,
            version = version + 1
        WHERE public_id = $1::uuid
          AND version = $2::bigint
          AND deleted_at IS NULL
          AND (NOT $7::boolean OR $8::uuid IS NULL OR EXISTS (SELECT 1 FROM game WHERE public_id = $8::uuid))
        RETURNING id, public_id"""

    /** The links to every expansion but the ones to keep. `$1` playset id, `$2` expansion public ids to keep. */
    @Language("PostgreSQL")
    const val DELETE_EXPANSIONS_EXCEPT: String = """
        DELETE FROM playset_expansion
        WHERE playset_id = $1::bigint
          AND expansion_id NOT IN (SELECT id FROM expansion WHERE public_id = ANY($2::uuid[]))"""

    /** Every note of the playsets, soft-deleted ones included. `$1` playset ids. */
    @Language("PostgreSQL")
    const val NOTE_KEYS: String =
        "SELECT public_id, deleted_at IS NULL AS active FROM playset_note WHERE playset_id = ANY($1::bigint[])"

    /**
     * A note that already has a row, restoring it when it was soft-deleted. `$1` public id, `$2`
     * text, `$3` playset id, `$4` game public id.
     */
    @Language("PostgreSQL")
    const val UPDATE_NOTE: String = """
        UPDATE playset_note
        SET text = $2::varchar,
            playset_id = $3::bigint,
            game_id = (SELECT id FROM game WHERE public_id = $4::uuid),
            deleted_at = NULL
        WHERE public_id = $1::uuid
          AND ($4::uuid IS NULL OR EXISTS (SELECT 1 FROM game WHERE public_id = $4::uuid))
        RETURNING public_id"""

    /** `$1` note public ids. Their reactions and loadouts stay: a soft-deleted note can be restored. */
    @Language("PostgreSQL")
    const val SOFT_DELETE_NOTES: String =
        "UPDATE playset_note SET deleted_at = now() WHERE public_id = ANY($1::uuid[]) AND deleted_at IS NULL"

    /** Every reaction of the notes. `$1` note public ids. */
    @Language("PostgreSQL")
    const val REACTION_KEYS: String =
        "SELECT public_id FROM note_reaction WHERE note_id = ANY($1::uuid[])"

    /** `$1` public id, `$2` emoji, `$3` note public id. */
    @Language("PostgreSQL")
    const val UPDATE_REACTION: String =
        "UPDATE note_reaction SET emoji = $2::varchar, note_id = $3::uuid WHERE public_id = $1::uuid"

    /** `$1` public ids. */
    @Language("PostgreSQL")
    const val DELETE_REACTIONS: String =
        "DELETE FROM note_reaction WHERE public_id = ANY($1::uuid[])"

    // Delete

    /** Matches nothing when the row is gone or its version moved on. `$1` public id, `$2` expected version. */
    @Language("PostgreSQL")
    const val SOFT_DELETE: String = """
        UPDATE playset
        SET deleted_at = now(), version = version + 1
        WHERE public_id = $1::uuid AND version = $2::bigint AND deleted_at IS NULL
        RETURNING id"""

    /** The notes of soft-deleted playsets. `$1` playset ids. */
    @Language("PostgreSQL")
    const val SOFT_DELETE_NOTES_OF: String =
        "UPDATE playset_note SET deleted_at = now() WHERE playset_id = ANY($1::bigint[]) AND deleted_at IS NULL"

    // Checks, run only to explain a write that matched nothing

    /** `$1` public id. */
    @Language("PostgreSQL")
    const val ACTIVE_ROW: String =
        "SELECT 1 FROM playset WHERE public_id = $1::uuid AND deleted_at IS NULL"

    /** `$1` game public id. */
    @Language("PostgreSQL")
    const val GAME_EXISTS: String = "SELECT 1 FROM game WHERE public_id = $1::uuid"

    /** `$1` user public id. */
    @Language("PostgreSQL")
    const val OWNER_EXISTS: String = "SELECT 1 FROM app_user WHERE public_id = $1::uuid"

    /** The expansions that exist among the ones a command links. `$1` expansion public ids. */
    @Language("PostgreSQL")
    const val EXISTING_EXPANSIONS: String =
        "SELECT public_id FROM expansion WHERE public_id = ANY($1::uuid[])"
}
