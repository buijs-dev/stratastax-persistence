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

private const val DEFAULT_SNAPSHOT_ROOT = "src/test/resources/persistence/snapshots"

/**
 * Records (on first run) or compares (on every run after) each observed query's SQL - and, when a
 * [QueryPlanAnalyzer] is configured, its plan *shape* (see [PlanNode.renderTree]) - against files
 * under [directory]/[name]/query-NNN.{sql,plan.txt}.
 *
 * Unlike the other assertions here, a snapshot doesn't encode "this must always be true" - it
 * encodes "tell me when this changed," which is exactly what's useful while iterating on the code
 * generator: an unexpected SQL/plan shape change surfaces immediately, with the actual
 * before/after, instead of silently drifting.
 *
 * [directory] defaults to a path relative to the test JVM's working directory - Gradle/most IDEs
 * run tests with the consumer module's own root as that directory, so the default resolves under
 * that module's own `src/test/resources` without persistence-testkit needing to know anything about
 * the consumer's layout. Pass an explicit [directory] if that default doesn't fit.
 */
fun <T> TestExecution<T>.assertMatchesSnapshot(
    name: String,
    directory: Path = Path.of(DEFAULT_SNAPSHOT_ROOT),
): TestExecution<T> {
    val snapshotDir = directory.resolve(name)
    val mismatches = mutableListOf<String>()

    queries.forEachIndexed { index, query ->
        val label = "query-%03d".format(index + 1)
        val sql = query.execution.query.sql
        val planTree = query.plan?.root?.renderTree()

        val sqlFile = snapshotDir.resolve("$label.sql")
        if (!Files.exists(sqlFile)) {
            Files.createDirectories(snapshotDir)
            Files.writeString(sqlFile, sql)
        } else {
            val previous = Files.readString(sqlFile)
            if (previous != sql) {
                mismatches +=
                    "Query #${index + 1}\n    SQL changed\n\n" +
                    "Previous:\n$previous\n\nCurrent:\n$sql"
            }
        }

        if (planTree != null) {
            val planFile = snapshotDir.resolve("$label.plan.txt")
            if (!Files.exists(planFile)) {
                Files.createDirectories(snapshotDir)
                Files.writeString(planFile, planTree)
            } else {
                val previous = Files.readString(planFile)
                if (previous != planTree) {
                    mismatches +=
                        "Query #${index + 1}\n    Execution plan changed\n\n" +
                        "Previous:\n$previous\n\nCurrent:\n$planTree"
                }
            }
        }
    }

    if (mismatches.isNotEmpty()) {
        throw AssertionError(
            "Persistence snapshot changed\n\n$name\n\n" + mismatches.joinToString("\n\n"),
        )
    }

    return this
}
