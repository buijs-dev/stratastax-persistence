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

import dev.buijs.stratastax.entity.core.api.EntityField
import dev.buijs.stratastax.entity.core.api.EntityFieldType
import dev.buijs.stratastax.entity.core.api.PatchField
import dev.buijs.stratastax.persistence.core.spi.RowMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The playset as the application sees it, with every kind of relation: an optional game, an owner,
 * an owned loadout, expansions through a link table and notes with reactions.
 */
internal data class PlaysetEntity(
    val id: UUID,
    val name: String,
    val createdAt: OffsetDateTime,
    val version: Long,
    val attributes: Map<String, Any?>?,
    val gameName: String?,
    val ownerName: String,
    val loadoutLabel: String?,
    val expansions: List<String>,
    val notes: List<Note>,
)

internal data class Note(
    val id: UUID,
    val text: String,
    val gameName: String?,
    val reactions: List<String>,
    val loadoutLabel: String?,
)

internal data class CreatePlayset(
    val name: String,
    val ownerId: UUID,
    val id: UUID = UUID.randomUUID(),
    val gameId: UUID? = null,
    val attributesJson: String? = null,
    val expansionIds: Set<UUID>? = null,
    val notes: List<CreateNote> = emptyList(),
    val loadout: CreateLoadout? = null,
)

internal data class CreateNote(
    val text: String,
    val id: UUID = UUID.randomUUID(),
    val gameId: UUID? = null,
    val reactions: List<CreateReaction> = emptyList(),
    val loadout: CreateLoadout? = null,
)

internal data class CreateReaction(
    val emoji: String,
    val id: UUID = UUID.randomUUID(),
)

internal data class CreateLoadout(
    val label: String,
)

internal data class UpdatePlayset(
    val name: PatchField<String> = PatchField.Unset,
    val gameId: PatchField<UUID?> = PatchField.Unset,
    val attributesJson: PatchField<String?> = PatchField.Unset,
    val expansionIds: PatchField<Set<UUID>?> = PatchField.Unset,
    val notes: PatchField<List<CreateNote>> = PatchField.Unset,
)

/** The search fields and the row mapper of [PlaysetEntity], as codegen would generate them. */
internal object PlaysetModel {

    val idField = EntityField("id", EntityFieldType.UUID, sortable = true, filterable = true)
    val nameField = EntityField("name", EntityFieldType.STRING, sortable = true, filterable = true)
    val createdAtField =
        EntityField("createdAt", EntityFieldType.DATETIME, sortable = true, filterable = true)
    val gameNameField =
        EntityField("gameName", EntityFieldType.STRING, sortable = true, filterable = true)
    val ownerNameField =
        EntityField("ownerDisplayName", EntityFieldType.STRING, sortable = true, filterable = true)
    val notesTextField = EntityField("notesText", EntityFieldType.STRING, filterable = true)
    val expansionsNameField =
        EntityField("expansionsName", EntityFieldType.STRING, filterable = true)
    val reactionsEmojiField =
        EntityField("notesReactionsEmoji", EntityFieldType.STRING, filterable = true)

    val rowMapper =
        RowMapper { row ->
            PlaysetEntity(
                id = row.uuid("id"),
                name = row.string("name"),
                createdAt = row.offsetDateTime("createdAt"),
                version = row.long("version"),
                attributes = row.mapOrNull("attributes"),
                gameName = row.stringOrNull("gameName"),
                ownerName = row.string("ownerDisplayName"),
                loadoutLabel = row.stringOrNull("loadoutLabel"),
                expansions = row.objectList("expansions").map { it.string("expansionsName") },
                notes =
                    row.objectList("notes").map { note ->
                        Note(
                            id = note.uuid("notesId"),
                            text = note.string("notesText"),
                            gameName = note.stringOrNull("notesGameName"),
                            reactions =
                                note
                                    .objectList("notesReactions")
                                    .map { it.string("notesReactionsEmoji") },
                            loadoutLabel = note.stringOrNull("notesLoadoutLabel"),
                        )
                    },
            )
        }
}

/** Inserts reference data over JDBC and returns its public id. */
internal object Seed {

    fun game(name: String): UUID = insert("game", "name", name)

    fun user(displayName: String): UUID = insert("app_user", "display_name", displayName)

    fun expansion(name: String): UUID = insert("expansion", "name", name)

    private fun insert(
        table: String,
        column: String,
        value: String,
    ): UUID {
        val id = UUID.randomUUID()
        Database.jdbc(
            "INSERT INTO $table (public_id, $column) VALUES ('$id', '${value.replace("'", "''")}')",
        )
        return id
    }
}
