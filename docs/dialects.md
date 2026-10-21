# SQL dialects

The statements are literal SQL, so each set of statements is written for one database. The runtime
in `persistence-reactive` is database-neutral: everything it needs to know about the database sits
behind one interface, `PersistenceDialect`. **PostgreSQL** is the dialect that ships, as
`PostgresDialect` in `persistence-postgres`. Another database is a module like that one, and it can
live in a repository of its own.

## The dialect interface

```kotlin
public interface PersistenceDialect {
    val transactions: TransactionSql      // the BEGIN / SAVEPOINT / ... statements
    val search: SearchSyntax              // the pieces a search is rendered from
    fun columnKey(name: String): String   // how the database folds an unquoted name
    fun bindValue(value: Any?): Any?      // a parameter as its Vert.x client binds it (has a default)
}

public interface SearchSyntax {
    fun parameter(position: Int, type: SqlType): String                    // `$3::uuid`, `?`
    fun arrayParameter(position: Int, type: SqlType): String? = null       // null: an IN (...) list
    fun anyOf(column: String, array: String): String = "$column = ANY($array)"
    fun noneOf(column: String, array: String): String = "$column <> ALL($array)"
    fun like(column: String, pattern: String, ignoreCase: Boolean): String
    fun orderBy(column: String, direction: SortDirection): String         // NULL sorts as largest
    fun limit(parameter: String): String = "LIMIT $parameter"
    fun skipLocked(lockClause: String): String = "$lockClause SKIP LOCKED"
    val rowValueComparison: Boolean get() = false                          // `(a, b) > ($1, $2)`
    val alwaysTrue: String get() = "TRUE"
    val alwaysFalse: String get() = "FALSE"
}
```

The dialect goes to the executors, which use it for transactions, column names and parameter
binding:

```kotlin
val transactional = VertxTransactionalExecutor(pool, PostgresDialect)
```

It also goes into every `SearchSql`, because the generated fragments of a search are written for
the same database as the parts rendered at runtime:

```kotlin
SearchSql(dialect = PostgresDialect, select = PlaysetSql.SELECT, /* ... */)
```

The runtime decides how a search is put together: the conditions, the keyset seek, the `EXISTS`
along a relation and the parameter order. The dialect decides how each piece is written.

## How PostgreSQL is handled

| Concern        | `PostgresDialect`                                                                                                                                      |
|----------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| placeholders   | `$1, $2, ...`, written in the statements, each with a `::type` cast                                                                                    |
| arrays         | several values of a uuid, text, varchar, integer or bigint field are one parameter: `= ANY($1::uuid[])`, `<> ALL(...)`                                |
| search         | `ILIKE` for a case-insensitive like, `ASC NULLS LAST` / `DESC NULLS FIRST`, row-value seeks, `LIMIT`, `FOR UPDATE OF <table> SKIP LOCKED`             |
| transactions   | `BEGIN [ISOLATION LEVEL ...][, READ ONLY]`, `SAVEPOINT`, `RELEASE SAVEPOINT`, `ROLLBACK TO SAVEPOINT`                                                  |
| names          | unquoted names are lowercased. A result column is looked up exactly first, then lowercased, so `row.string("createdAt")` finds the column `createdat`. |
| reserved words | code generation gives a relation named like a reserved word (`user`, `order`) another alias, so nothing needs quoting                                  |

PostgreSQL-compatible databases, such as CockroachDB and YugabyteDB, work with `PostgresDialect` as
it is.

## What the generated statements assume

The dialect covers what the runtime renders. The statements code generation writes are a separate
question. Today they use, besides the syntax above:

- `INSERT / UPDATE / DELETE ... RETURNING`, so a write returns its keys (MySQL has no `RETURNING`);
- `jsonb_agg` and `jsonb_build_object` to read one-to-many relations as JSON arrays;
- array columns (`text[]`);
- `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` in the test kit (`PostgresQueryPlanAnalyzer`, behind
  the `QueryPlanAnalyzer` interface).

## Writing a dialect in another repository

1. **A module** that depends on `persistence-reactive` and on the Vert.x Mutiny client of your
   database (e.g. `smallrye-mutiny-vertx-mysql-client`):

   ```kotlin
   dependencies {
       api("dev.buijs.stratastax:persistence-reactive:<version>")
       api("io.smallrye.reactive:smallrye-mutiny-vertx-mysql-client:<version>")
   }
   ```

2. **A `PersistenceDialect`**, built from the public `spi` package only:

   ```kotlin
   public object MySqlDialect : PersistenceDialect {
       override val transactions: TransactionSql =
           object : TransactionSql {
               override fun begin(options: TransactionOptions): String = "START TRANSACTION"
           }

       override val search: SearchSyntax =
           object : SearchSyntax {
               override fun parameter(position: Int, type: SqlType): String = "?"

               override fun like(column: String, pattern: String, ignoreCase: Boolean): String =
                   if (ignoreCase) "LOWER($column) LIKE LOWER($pattern)" else "$column LIKE $pattern"

               // NULL sorts as the largest value.
               override fun orderBy(column: String, direction: SortDirection): String =
                   when (direction) {
                       SortDirection.ASC -> "$column IS NULL, $column ASC"
                       SortDirection.DESC -> "$column IS NOT NULL, $column DESC"
                   }

               override val rowValueComparison: Boolean = true
           }

       override fun columnKey(name: String): String = name.lowercase()
   }
   ```

   Leave out what the defaults already cover. Override `bindValue` for the types your client
   encodes differently, and call `super.bindValue(value)` for the rest. Define your own `SqlType`
   values when the database has other type names: `SqlType("datetime", LocalDateTime::class.java)`.

3. **Statements written for it.** A code generation target that writes the dialect's statements
   and puts the dialect in each generated `SearchSql`.

4. **The integration tests** of `persistence-postgres` (`integration/*`, `sqlfirst/*`), run against
   the new database. `persistence-reactive`'s `ForeignDialectRenderTest` shows a non-Postgres
   dialect (`?` placeholders, no arrays, no row values, `FETCH FIRST`) rendering searches from
   outside the module.

---

Back to the [introduction](introduction.md).
