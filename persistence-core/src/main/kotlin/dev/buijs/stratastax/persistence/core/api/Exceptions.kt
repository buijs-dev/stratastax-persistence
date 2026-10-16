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
 * Thrown when an update or delete targets an [id] that has no row in [entityName].
 *
 * Distinct from [OptimisticLockException], where the row exists but its version moved on.
 */
public class EntityNotFoundException(
    public val entityName: String,
    public val id: Any,
) : IllegalArgumentException("No row in '$entityName' where id = $id")

/**
 * Thrown when an update or delete is rejected because [expectedVersion] no longer matches the
 * version of row [id] in [entityName]: someone else modified (or deleted) it in the meantime.
 */
public class OptimisticLockException(
    public val entityName: String,
    public val id: Any,
    public val expectedVersion: Any?,
) : IllegalStateException(
        "$entityName($id): update/delete rejected. Expected version $expectedVersion no longer " +
            "matches the current row (it was modified or deleted by someone else)",
    )

/**
 * Thrown when a create or update command references another entity (e.g. by its public id) that
 * does not exist: there is no row in [tableName] where [columnName] equals [value].
 */
public class ReferencedEntityNotFoundException(
    public val tableName: String,
    public val columnName: String,
    public val value: Any?,
) : IllegalArgumentException(
        "No row in '$tableName' where '$columnName' = $value, referenced entity not found",
    )

/**
 * Thrown by [orThrow] when at least one input of a batch call failed.
 *
 * [failures] holds every failed input's exception, in input order. The first one is the [cause];
 * the others are attached as [suppressed][Throwable.getSuppressed] exceptions so they show up in a
 * stack trace too.
 */
public class BatchFailedException(
    public val failures: List<Throwable>,
) : RuntimeException(
        "${failures.size} batch item(s) failed, first: ${failures.firstOrNull()?.message}",
        failures.firstOrNull(),
    ) {

    init {
        failures.drop(1).forEach(::addSuppressed)
    }
}
