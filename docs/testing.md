# Testing persistence code

A unit test with a fake repository tells you the service logic is right. It does not tell you that
a page of 50 playsets is one query rather than 51, that the search uses an index, or that a
change to the generator quietly added a statement.

`persistence-test` answers those questions. It records the SQL your code actually runs against a
real PostgreSQL, and lets you assert on it: how many queries, which ones repeat, what the planner
does with them, and how they are grouped in transactions.

```kotlin
testImplementation("dev.buijs.stratastax:persistence-test:<version>")
```

```kotlin
harness.execute { playsets.findAll(search) }
    .assertQueryCount(1)
    .assertNoNPlusOne()
    .assertUsesIndex("playset_name_idx")
```

## Set up

Three pieces: a database, a recorder that sees every statement, and the harness that ties them
together.

```kotlin
class PlaysetQueriesTest {

    companion object {
        val database = PostgresTestDatabase()          // postgres:16-alpine in Testcontainers

        @BeforeAll @JvmStatic
        fun start() {
            database.start()                           // skips the test class without Docker
            database.executeDdl("CREATE TABLE playset (...)")
        }
    }

    private val recorder = QueryExecutionRecorder()
    private val transactional = VertxTransactionalExecutor(pool, listeners = listOf(recorder))
    private val harness = PersistenceTestHarness(recorder, database.planAnalyzer())
}
```

Give the same recorder to the executors the code under test uses. The harness only reports what ran
while its block ran.

Leave out the plan analyzer when no test needs query plans: analyzing runs every statement a second
time.

## Assertions

```kotlin
harness.execute { playsets.findAll(search) }
    .assertSuccessful()                    // the block did not throw
    .assertQueryCount(1)                   // data statements only; BEGIN/COMMIT are not counted
    .assertNoNPlusOne()                    // no SQL text ran 3 or more times
    .assertNoSequentialScans()             // the planner used no full table scan
    .assertMaxRowsRead(100)
    .assertQuery { usesTable("playset"); usesPredicate("name"); doesNotContain("order by") }
```

`execute` does not throw when the block throws: the error is kept in `execution.error`, so you can
assert on failures too.

| Assertion                                                                                                             | Checks                                    |
|-----------------------------------------------------------------------------------------------------------------------|-------------------------------------------|
| `assertSuccessful`, `assertNoFailedQueries`                                                                           | the block, or no single statement, failed |
| `assertQueryCount(n)`                                                                                                 | exactly `n` data statements ran           |
| `assertNoNPlusOne(threshold)`                                                                                         | no SQL text ran `threshold` times or more |
| `assertNoSequentialScans`, `assertUsesIndex`, `assertMaxRowsRead`, `assertMaxBufferReads`, `assertExecutionTimeBelow` | the query plans (needs the analyzer)      |
| `assertCommitted`, `assertRolledBack`, `assertSingleTransaction`                                                      | the transactions                          |
| `assertQuery { ... }`                                                                                                 | what the SQL text contains                |

When an assertion fails, the message lists every statement that ran, with its position,
transaction, operation and SQL, so you can see what happened without a debugger.

## Scenarios: every check at once

A chain of assertions stops at the first failure. A scenario runs every check, also after one
failed, and reports them together:

```kotlin
harness.scenario("search playsets by name") {
    execute { playsets.findAll(byName) }
    expect {
        queries = 1
        sql { usesPredicate("name") }
        plan {
            noSequentialScans()
            maxRowsRead = 10
        }
    }
}
```

```
✗ search playsets by name

SQL
  ✓ 1 query executed
  ✓ predicate name

PLAN
  ✗ no sequential scans
    playset
  ✓ rows read <= 10
```

## Snapshots: notice when the SQL changes

```kotlin
harness.execute { playsets.create(command) }.assertMatchesSnapshot("create playset")
```

The first run records the SQL of every statement, and with an analyzer the shape of its plan, in
`src/test/resources/persistence/snapshots/create playset/`. Commit those files. Later runs fail when
a statement changed, was added or no longer runs, and show the before and after.

This makes changes to the SQL visible in code review, for example after upgrading the code
generator.

**After an intended change**, re-record by running the tests with the system property
`persistence.snapshots.update=true`. Stale files are removed. With Gradle, pass the property on to
the test JVM:

```kotlin
tasks.test {
    System.getProperty("persistence.snapshots.update")?.let { systemProperty("persistence.snapshots.update", it) }
}
```

```shell
./gradlew test -Dpersistence.snapshots.update=true
```

On CI (`CI=true`) a missing snapshot fails the test instead of being recorded.

## Query plans

`PostgresQueryPlanAnalyzer` runs `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` for every recorded
statement on a connection of its own, in a transaction that is always rolled back. Analyzing an
`INSERT`, `UPDATE` or `DELETE` therefore leaves no trace.

A `QueryPlan` offers `sequentialScans()`, `indexesUsed()`, `rowsRead()`, `bufferReads()`, and
`root.renderTree()` to print it.

## Looking at the database directly

`PostgresTestDatabase` can also check what was committed, outside the code under test:

```kotlin
database.queryForLong("SELECT count(*) FROM playset")
```

For the server's own view, `enableStatementLogging()` and `logs()` show which statement ran on which
connection and in which transaction.

---

Next: [Code generation](code-generation.md)
