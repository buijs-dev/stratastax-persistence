# SQL dialects

The statements are literal SQL, so they are written for one database: PostgreSQL. What the runtime
itself needs to know about the database sits behind `PersistenceDialect`; `PostgresDialect` is the
implementation in use and the default everywhere.

```kotlin
public interface PersistenceDialect {
    val transactions: TransactionSql           // BEGIN / SAVEPOINT / ... statements
    fun columnKey(name: String): String        // how the database folds an unquoted identifier
}
```

`VertxQueryExecutor` and `VertxTransactionalExecutor` take the dialect as a constructor argument.

## PostgreSQL

| Concern | PostgreSQL |
|---|---|
| placeholders | `$1, $2, ...`, written in the statements themselves, each with a `::type` cast |
| transactions | `BEGIN [ISOLATION LEVEL ...][, READ ONLY]`, `SAVEPOINT`, `RELEASE SAVEPOINT`, `ROLLBACK TO SAVEPOINT` |
| identifiers | unquoted, folded to lowercase; a result column is matched exactly first and then by its folded name, so `row.string("createdAt")` reads the column `createdat` |
| reserved words | code generation aliases a relation named like a reserved word (`user`, `order`) so it never needs quoting |

## What is PostgreSQL-only

The generated statements and the search rendering use:

- `INSERT/UPDATE/DELETE ... RETURNING` - writes return their keys (MySQL has no `RETURNING`);
- `jsonb_agg` / `jsonb_build_object` for one-to-many relations, read as JSON arrays;
- `= ANY($1::uuid[])` with an array parameter for id lists, and array columns (`text[]`, ...);
- `::type` casts on every parameter;
- `SELECT ... FOR UPDATE OF <table> [SKIP LOCKED]` (`findAllForUpdate`);
- `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` in `persistence-test`.

PostgreSQL-compatible databases - CockroachDB, YugabyteDB - work with `PostgresDialect` as is.

## Another database

Another database needs both halves:

1. A `PersistenceDialect` for its transaction statements and identifier folding, passed to the
   executors.
2. Statements written for it: a code generation target that emits its SQL, and a search rendering
   (`SearchSqlWriter`) for its placeholder style and row-value comparisons.

Then run the integration tests against it.
