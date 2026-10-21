# SQL dialects

The statements are literal SQL, so they are written for one database: **PostgreSQL**. This page
lists what depends on that, and what it would take to support another database.

## The dialect interface

What the runtime itself needs to know about the database is small, and sits behind
`PersistenceDialect`:

```kotlin
public interface PersistenceDialect {
    val transactions: TransactionSql           // the BEGIN / SAVEPOINT / ... statements
    fun columnKey(name: String): String        // how the database folds an unquoted name
}
```

`PostgresDialect` is the implementation, and the default of `VertxQueryExecutor` and
`VertxTransactionalExecutor`. You only pass a dialect explicitly when you write your own.

## How PostgreSQL is handled

| Concern        | PostgreSQL                                                                                                                                             |
|----------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| placeholders   | `$1, $2, ...`, written in the statements, each with a `::type` cast                                                                                    |
| transactions   | `BEGIN [ISOLATION LEVEL ...][, READ ONLY]`, `SAVEPOINT`, `RELEASE SAVEPOINT`, `ROLLBACK TO SAVEPOINT`                                                  |
| names          | unquoted names are lowercased. A result column is looked up exactly first, then lowercased, so `row.string("createdAt")` finds the column `createdat`. |
| reserved words | code generation gives a relation named like a reserved word (`user`, `order`) another alias, so nothing needs quoting                                  |

## What is PostgreSQL-only

The generated statements and the search rendering use:

- `INSERT / UPDATE / DELETE ... RETURNING`, so a write returns its keys (MySQL has no `RETURNING`);
- `jsonb_agg` and `jsonb_build_object` to read one-to-many relations as JSON arrays;
- array parameters (`= ANY($1::uuid[])`) and array columns (`text[]`);
- `::type` casts on every parameter;
- `SELECT ... FOR UPDATE OF <table> SKIP LOCKED` for work queues;
- `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` in the test kit.

PostgreSQL-compatible databases, such as CockroachDB and YugabyteDB, work with `PostgresDialect` as
it is.

## Supporting another database

Another database needs two things:

1. **A `PersistenceDialect`** with its transaction statements and its way of folding names, passed
   to the executors.
2. **SQL written for it.** A code generation target that writes its statements, and a search
   renderer for its placeholder style and row comparisons (now `SearchSqlWriter`, internal to
   `persistence-reactive`).

Then run the integration tests against it.

---

Back to the [introduction](introduction.md).
