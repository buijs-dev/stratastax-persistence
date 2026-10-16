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

internal object TestFields {

    val idField = EntityField(name = "id", type = EntityFieldType.UUID, sortable = true)
    val statusField = EntityField(name = "status", type = EntityFieldType.STRING, filterable = true)
    val playedAtField = EntityField(name = "playedAt", type = EntityFieldType.DATETIME)
    val gameNameField =
        EntityField(name = "gameName", type = EntityFieldType.STRING, sortable = true)

    val recorderUserDisplayNameField =
        EntityField(
            name = "recorderUserDisplayName",
            type = EntityFieldType.STRING,
            sortable = true,
        )
    val recorderUserTeamNameField =
        EntityField(name = "recorderUserTeamName", type = EntityFieldType.STRING, filterable = true)
    val lastEditedByUserDisplayNameField =
        EntityField(
            name = "lastEditedByUserDisplayName",
            type = EntityFieldType.STRING,
            filterable = true,
        )

    val tagsNameField =
        EntityField(name = "tagsName", type = EntityFieldType.STRING, filterable = true)

    val tagsIdField = EntityField(name = "tagsId", type = EntityFieldType.UUID, filterable = true)

    val attributesField =
        EntityField(name = "attributes", type = EntityFieldType.JSON, filterable = true)

    val participantsNameField =
        EntityField(name = "participantsName", type = EntityFieldType.STRING, filterable = true)
    val participantsScoreField =
        EntityField(name = "participantsScore", type = EntityFieldType.NUMBER, filterable = true)
    val participantsGhostPlayerDisplayNameField =
        EntityField(
            name = "participantsGhostPlayerDisplayName",
            type = EntityFieldType.STRING,
            filterable = true,
        )

    val participantsIdField =
        EntityField(name = "participantsId", type = EntityFieldType.UUID, filterable = true)

    val deletedAtField = EntityField(name = "deletedAt", type = EntityFieldType.STRING)
}
