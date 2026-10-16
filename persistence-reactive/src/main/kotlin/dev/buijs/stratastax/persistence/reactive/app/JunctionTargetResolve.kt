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

import org.jooq.TableField

/**
 * Resolves a junction's own supplied target ids (a public id from the create/update command, e.g.
 * `EXPANSION.PUBLIC_ID`) to the target table's own raw column value (e.g. `EXPANSION.ID`) before
 * writing - the list-shaped analog of [GroupFkResolve], for when the command supplies a public id
 * rather than the physical FK's own raw value. Unlike [GroupFkResolve] (one scalar value, resolved
 * inline as a joined derived table in the same `INSERT`/ `UPDATE` statement), a junction can write
 * many rows per call, so [JooqCreateAccess]/[JooqUpdateAccess] resolve every supplied id in one
 * batched `SELECT ... WHERE [identityColumn] IN (...)` lookup first, then write the junction rows
 * using the resolved ids - throwing
 * [dev.buijs.stratastax.persistence.core.api.ReferencedEntityNotFoundException] for any supplied id
 * with no matching row, rather than silently writing fewer rows than were supplied.
 *
 * Set via a junction group field's own `persistence { id() }` (see
 * `dev.buijs.stratastax.persistence.reactive.GroupFieldPersistenceBuilder.id` - not to be confused
 * with the unrelated `EntityFieldPersistenceBuilder.id` a *root* field calls) - optional; when no
 * field in a junction group calls this, [JunctionWrite]'s own `targetResolve` stays `null` and the
 * command's supplied target ids are bound directly, unchanged from before this existed (correct
 * whenever they already are the target table's own raw id).
 *
 * @property identityColumn the target table's own public identity column, e.g.
 *   `EXPANSION.PUBLIC_ID` - what the command's supplied ids are matched against.
 * @property rawColumn what the resolve query actually selects and what gets bound into
 *   [JunctionWrite.targetColumn], e.g. `EXPANSION.ID`.
 */
class JunctionTargetResolve(
    val identityColumn: TableField<*, *>,
    val rawColumn: TableField<*, *>,
)
