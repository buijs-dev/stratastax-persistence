# Testing persistence code

`persistence-test` asserts on the SQL your code actually runs against PostgreSQL: how many
queries, which ones repeat (N+1), what the planner does with them, how they are grouped in
transactions, and whether they changed since last time.

```kotlin
testImplementation("dev.buijs.stratastax:persistence-test:<version>")
```

## Set up

```kotlin
class PlaysetQueriesTest {

    companion object {
        val database = PostgresTestDatabase()          // postgres:16-alpine in Testcontainers

        @BeforeAll @JvmStatic
        fun start() {
            database.start()                           // skips the class without Docker
            database.executeDdl("CREATE TABLE playset (...)")
        }
    }

    private val recorder = QueryExecutionRecorder()
    private val transactional = VertxTransactionalExecutor(pool, listeners = listOf(recorder))
    private val harness = PersistenceTestHarness(recorder, database.planAnalyzer())
}
```

Wire the same `QueryExecutionRecorder` into the executors the code under test uses; the harness
reports only what ran while its block ran. Omit the analyzer when no test needs plans - analyzing
runs every statement once more.

## Assertions

```kotlin
val execution = harness.execute { playsets.findAll(search) }

execution
    .assertSuccessful()                    // the block did not throw
    .assertQueryCount(1)                   // data statements; BEGIN/COMMIT/... are not counted
    .assertNoNPlusOne()                    // no SQL text ran 3 or more times
    .assertNoSequentialScans()             // plan: no full table scans
    .assertUsesIndex("playset_name_idx")
    .assertMaxRowsRead(100)
    .assertQuery { usesTable("playset"); usesPredicate("name"); doesNotContain("order by") }
```

`execute` captures what the block threw in `execution.error` instead of throwing it.

| Assertion | Checks |
|---|---|
| `assertSuccessful`, `assertNoFailedQueries` | the block, or no statement, failed |
| `assertQueryCount(n)` | exactly `n` data statements ran |
| `assertNoNPlusOne(threshold)` | no SQL text ran `threshold` times or more |
| `assertNoSequentialScans`, `assertUsesIndex`, `assertMaxRowsRead`, `assertMaxBufferReads`, `assertExecutionTimeBelow` | the plans (needs an analyzer) |
| `assertCommitted`, `assertRolledBack`, `assertSingleTransaction` | the transaction statements |
| `assertQuery { ... }` | what the SQL text references |

A failing assertion lists every statement that ran: position, transaction, operation and SQL.

## Scenarios

A scenario runs every check, also after one failed, and reports them together:

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

## Snapshots

```kotlin
harness.execute { playsets.create(command) }.assertMatchesSnapshot("create playset")
```

The first run records the SQL of every statement (and, with an analyzer, the plan shape) under
`src/test/resources/persistence/snapshots/create playset/`. Later runs fail when a statement
changed, was added or no longer runs - with the before and after. Commit the snapshots.

- Re-record after an intended change by setting the system property
  `persistence.snapshots.update=true` in the test JVM - stale files are removed. With Gradle, pass
  it on from the command line:

  ```kotlin
  tasks.test {
      System.getProperty("persistence.snapshots.update")?.let { systemProperty("persistence.snapshots.update", it) }
  }
  ```

  and run `./gradlew test -Dpersistence.snapshots.update=true`.
- On CI (`CI=true`) a missing snapshot fails instead of being recorded.

## Query plans

`PostgresQueryPlanAnalyzer` runs `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` on its own JDBC
connection, inside a transaction that is always rolled back - analyzing an `INSERT`, `UPDATE` or
`DELETE` leaves no trace. A `QueryPlan` offers `sequentialScans()`, `indexesUsed()`, `rowsRead()`,
`bufferReads()` and `root.renderTree()`.

## The database

`PostgresTestDatabase` also offers `query(sql)` / `queryForLong(sql)` to check what was committed,
outside the code under test, and `enableStatementLogging()` + `logs()` for the server's own view of
which statement ran on which connection and in which transaction.
