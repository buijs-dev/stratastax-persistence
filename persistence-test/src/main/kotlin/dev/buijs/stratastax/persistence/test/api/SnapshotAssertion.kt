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
package dev.buijs.stratastax.persistence.test.api

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val DEFAULT_SNAPSHOT_ROOT = "src/test/resources/persistence/snapshots"

/** The system property that, set to `true`, (re)records every snapshot instead of comparing. */
public const val UPDATE_SNAPSHOTS_PROPERTY: String = "persistence.snapshots.update"

/**
 * Compares the statements of this execution - their SQL and, with a [QueryPlanAnalyzer], their
 * plan shape (see [PlanNode.renderTree]) - with the snapshot recorded under [directory]/[name], as
 * `query-001.sql`, `query-001.plan.txt`, ... A snapshot says "tell me when this changes": a changed,
 * added or removed statement fails with the before and after.
 *
 * - A missing snapshot is recorded on first run - except on CI (environment variable `CI` set to
 *   `true`), where it fails, so a forgotten snapshot never passes unnoticed.
 * - With the system property [UPDATE_SNAPSHOTS_PROPERTY] set to `true`, the snapshot is
 *   re-recorded: changed files are overwritten and files of statements that no longer run are
 *   deleted.
 *
 * [directory] is relative to the test's working directory, by default the module's own
 * `src/test/resources/persistence/snapshots`.
 */
public fun <T> TestExecution<T>.assertMatchesSnapshot(
    name: String,
    directory: Path = Path.of(DEFAULT_SNAPSHOT_ROOT),
): TestExecution<T> =
    matchSnapshot(
        name = name,
        directory = directory,
        update = System.getProperty(UPDATE_SNAPSHOTS_PROPERTY).toBoolean(),
        onCi = System.getenv("CI").toBoolean(),
    )

/** [assertMatchesSnapshot] with the update mode and the CI detection passed in. */
internal fun <T> TestExecution<T>.matchSnapshot(
    name: String,
    directory: Path,
    update: Boolean,
    onCi: Boolean,
): TestExecution<T> {
    val snapshotDir = directory.resolve(name)
    val current = snapshotFiles()
    when {
        update -> {
            record(snapshotDir, current)
        }
        !snapshotDir.exists() -> {
            if (onCi) {
                throw AssertionError(
                    "No persistence snapshot '$name' recorded under $snapshotDir - " +
                        "record it locally (or run with -D$UPDATE_SNAPSHOTS_PROPERTY=true) and commit it",
                )
            }

            record(snapshotDir, current)
        }
        else -> {
            val mismatches = compare(snapshotDir, current)
            if (mismatches.isNotEmpty()) {
                throw AssertionError(
                    "Persistence snapshot '$name' changed " +
                        "(re-record with -D$UPDATE_SNAPSHOTS_PROPERTY=true)\n\n" +
                        mismatches.joinToString("\n\n"),
                )
            }
        }
    }

    return this
}

/** The snapshot files of this execution by file name: the SQL of every statement, and its plan. */
private fun <T> TestExecution<T>.snapshotFiles(): Map<String, String> =
    queries
        .flatMapIndexed { index, query ->
            val label = "query-%03d".format(index + 1)
            listOfNotNull(
                "$label.sql" to query.execution.query.sql,
                query.plan?.let { "$label.plan.txt" to it.root.renderTree() },
            )
        }.toMap()

private fun record(
    directory: Path,
    files: Map<String, String>,
) {
    Files.createDirectories(directory)
    directory.listDirectoryEntries().filter { it.name !in files }.forEach { it.deleteIfExists() }

    files.forEach { (fileName, content) -> directory.resolve(fileName).writeText(content) }
}

private fun compare(
    directory: Path,
    files: Map<String, String>,
): List<String> {
    val recorded = directory.listDirectoryEntries().associate { it.name to it.readText() }

    val changed =
        files.mapNotNull { (fileName, content) ->
            val previous = recorded[fileName]
            when {
                previous == null -> {
                    "$fileName is new:\n$content"
                }
                previous != content -> {
                    "$fileName changed\n\nPrevious:\n$previous\n\nCurrent:\n$content"
                }
                else -> {
                    null
                }
            }
        }

    val removed =
        (recorded.keys - files.keys).sorted().map {
            "$it no longer runs:\n${recorded.getValue(it)}"
        }

    return changed + removed
}
