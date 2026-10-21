# Architecture

## Modules

```
persistence-core       API and SPI, no framework
   ▲        ▲
   │        └──────────────── persistence-csv       CSV rows through RowContext / RowMapper
   │
persistence-reactive   runs literal SQL on PostgreSQL with Vert.x; renders searches; the write kit

persistence-test       test kit (test scope): harness, assertions, plans, snapshots, PostgreSQL container
```

Module `persistence-core` is what application code depends on: the access interfaces, `UnitOfWork`,
`BatchResult` and the exceptions. Module `persistence-reactive` runs the statements behind them; only the
code that wires the application (and generated access classes) refers to it.

The SQL itself is not built by this library. Every statement of an entity is literal SQL, generated
by [stratastax-codegen](code-generation.md) into the consumer's build directory or written by hand.
The library runs it, binds its parameters, renders the request-dependent part of a search and
explains a write that matched nothing.

## Packages
Every module has the same three packages:

| Package    | For                                                                                   | Examples                                                                                                               |
|------------|---------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| `api`      | code that *uses* the library                                                          | `ReadAccess`, `UnitOfWork`, `EntityNotFoundException`, `VertxTransactionalExecutor`, `PostgresDialect`, `CsvRowReader` |
| `spi`      | code that *implements or configures* it - generated access classes, mappers, dialects | `QueryExecutor`, `RowMapper`, `RowContext`, `SearchSql`, `batchOrEach`, `diagnoseMiss`, `PersistenceDialect`           |
| `internal` | the module itself; Kotlin `internal`, invisible outside                               | `SearchSqlWriter`, `VertxRowContext`, the conversion of filter and cursor values                                       |

Every module is compiled in Kotlin's *explicit API mode*: a public declaration has to say it is
public, so nothing becomes API by accident. The public API is recorded in `<module>/api/*.api` and
`./gradlew build` fails when it changes unnoticed; run `./gradlew updateKotlinAbi` after an
intended change and commit the dump.

## The path of a read

```
SearchQuery ──► SearchSql.pageQuery ──► SqlStatement ──► QueryExecutor ──► PostgreSQL
 (search-core)    (spi)                   generated SELECT    (VertxQueryExecutor)
                                          + WHERE / ORDER BY
                                          + seek / LIMIT, $1, $2

PostgreSQL rows ──► RowContext (VertxRowContext) ──► RowMapper ──► your entity
```

1. A generated `ReadAccess` hands the `SearchQuery` to its entity's `SearchSql`: the read
   projection, the count and per field the column a condition and a sort use, all literal text.
2. `pageQuery` (or `listQuery`, `lockQuery`, `countQuery`) renders the filter, the sort, the keyset
   seek and the limit over those fragments, with numbered `$n` placeholders and their parameters.
3. The executor runs the statement as a Vert.x prepared query. Every row is read through a
   `RowContext` - by column name, typed - and mapped by the entity's `RowMapper`.
4. Every statement is reported to the `QueryExecutionListener`s: that is how the test kit sees
   what ran.

`findById` needs no rendering at all: it runs the generated `FIND_BY_ID` as is.

## The path of a write

A write - one entity or a batch - always runs in one transaction of the `TransactionalExecutor`
(or a savepoint of the caller's transaction). The generated access class runs the statements of
the entity's `Sql` object in order, top to bottom: owned rows, the root rows, links, children, and
one read-back. Each step is one `QueryExecutor.batch` for every input at once: one round trip, a
result per input. The write kit then checks the outcome of every input. See
[Writes and relations](writes-and-relations.md).

## Transactions travel with the coroutine

`VertxTransactionalExecutor` puts the running transaction in the coroutine context. Every
persistence call made in that coroutine - through the `AmbientQueryExecutor` the access classes
get - joins it. There is no thread-local state; see [Transactions](transactions.md).

## Background

Before, the generated code described an entity with configuration objects and this library built
the SQL from them at runtime with jOOQ. [SQL-first persistence](sql-first-design.md) explains why
that changed and how the move went.
