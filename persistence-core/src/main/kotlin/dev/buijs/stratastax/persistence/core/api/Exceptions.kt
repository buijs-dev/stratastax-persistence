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
package dev.buijs.stratastax.persistence.core.api

/**
 * Thrown when [id] itself has no matching row at all. This is distinct from
 * [OptimisticLockException] (the row exists but the caller's `expectedVersion` is stale). For an
 * entity with no version column configured, this is the *only* possible reason an update/delete
 * ever affects zero rows.
 */
class EntityNotFoundException(
    entityName: String,
    id: Any,
) : IllegalArgumentException("No row in '$entityName' where id = $id")

/**
 * Thrown when an update/delete command fails because the caller's `expectedVersion` is stale (the
 * row exists but the caller's `expectedVersion` is stale).
 */
class OptimisticLockException(
    entityName: String,
    id: Any,
    expectedVersion: Any?,
) : IllegalStateException(
        "$entityName($id): update/delete rejected. " +
            "Expected version $expectedVersion no longer matches " +
            "the current row (it was modified or deleted by someone else)",
    )

/**
 * Thrown when a create/update command references another entity by a public id (e.g., a `gameId`)
 * that doesn't actually exist.
 */
class ReferencedEntityNotFoundException(
    tableName: String,
    columnName: String,
    value: Any?,
) : IllegalArgumentException(
        "No row in '$tableName' where '$columnName' = $value, referenced entity not found",
    )

/**
 * Thrown by [dev.buijs.stratastax.persistence.core.spi.orThrow] when a batch call had at least one
 * failed input. This lets a caller turn a best-effort batch into an all-or-nothing one by throwing it
 * inside [UnitOfWork.transaction], which then rolls back the inputs that did succeed. [failures]
 * holds every failed input's own exception, in input order; the first one is also the [cause].
 */
class BatchFailedException(
    val failures: List<Throwable>,
) : RuntimeException(
        "${failures.size} batch item(s) failed, first: ${failures.firstOrNull()?.message}",
        failures.firstOrNull(),
    )
