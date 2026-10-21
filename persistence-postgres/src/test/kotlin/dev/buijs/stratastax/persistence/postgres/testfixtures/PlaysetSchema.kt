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
package dev.buijs.stratastax.persistence.postgres.testfixtures

/**
 * The test schema: a `playset` that belongs to an `app_user` (required one-to-one), may be for a
 * `game` (optional one-to-one), owns a `loadout` (owned one-to-one), links `expansion`s (junction)
 * and has `playset_note`s (one-to-many, soft-deletable and versioned) that each have
 * `note_reaction`s (nested one-to-many) and may reference a `game` themselves. `category` refers to
 * itself (`parent_id`).
 */
internal object PlaysetSchema {

    val TABLES =
        listOf(
            "note_reaction",
            "playset_note",
            "playset_expansion",
            "expansion",
            "playset",
            "loadout",
            "app_user",
            "game",
            "category",
        )

    val DDL =
        listOf(
            "CREATE TABLE game (id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL UNIQUE, name VARCHAR(255) NOT NULL)",
            "CREATE TABLE app_user (id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL UNIQUE, display_name VARCHAR(255) NOT NULL)",
            "CREATE TABLE loadout (id BIGSERIAL PRIMARY KEY, label VARCHAR(255) NOT NULL, deleted_at TIMESTAMPTZ)",
            """
            CREATE TABLE playset (
                id BIGSERIAL PRIMARY KEY,
                public_id UUID NOT NULL UNIQUE,
                name VARCHAR(255) NOT NULL,
                game_id BIGINT REFERENCES game (id),
                owner_id BIGINT NOT NULL REFERENCES app_user (id),
                loadout_id BIGINT REFERENCES loadout (id),
                attributes JSONB,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                version BIGINT NOT NULL DEFAULT 1,
                deleted_at TIMESTAMPTZ
            )
            """,
            "CREATE TABLE expansion (id BIGSERIAL PRIMARY KEY, public_id UUID NOT NULL UNIQUE, name VARCHAR(255) NOT NULL)",
            """
            CREATE TABLE playset_expansion (
                playset_id BIGINT NOT NULL REFERENCES playset (id),
                expansion_id BIGINT NOT NULL REFERENCES expansion (id),
                PRIMARY KEY (playset_id, expansion_id)
            )
            """,
            """
            CREATE TABLE playset_note (
                id BIGSERIAL PRIMARY KEY,
                public_id UUID NOT NULL UNIQUE,
                playset_id BIGINT NOT NULL REFERENCES playset (id),
                text VARCHAR(255) NOT NULL,
                game_id BIGINT REFERENCES game (id),
                loadout_id BIGINT REFERENCES loadout (id),
                version BIGINT NOT NULL DEFAULT 1,
                deleted_at TIMESTAMPTZ
            )
            """,
            """
            CREATE TABLE note_reaction (
                id BIGSERIAL PRIMARY KEY,
                public_id UUID NOT NULL UNIQUE,
                note_id UUID NOT NULL REFERENCES playset_note (public_id),
                emoji VARCHAR(16) NOT NULL
            )
            """,
            "CREATE TABLE category (id BIGSERIAL PRIMARY KEY, name VARCHAR(255) NOT NULL, parent_id BIGINT REFERENCES category (id))",
        ).map { it.trimIndent() }
}
