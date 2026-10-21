# Stratastax Persistence

[![Stratastax](https://img.shields.io/badge/Product-stratastax.dev-4F46E5?style=flat)](https://stratastax.dev/)
[![](https://img.shields.io/badge/Buijs-Software-blue)](https://buijs.dev/)
[![GitHub](https://img.shields.io/github/license/buijs-dev/stratastax-persistence?color=black)](https://github.com/buijs-dev/stratastax-persistence/blob/main/LICENSE)
[![codecov](https://codecov.io/gh/buijs-dev/stratastax-persistence/branch/main/graph/badge.svg)](https://codecov.io/gh/buijs-dev/stratastax-persistence)
[![CodeScene Average Code Health](https://codescene.io/projects/86238/status-badges/average-code-health)](https://codescene.io/projects/86238)
[![CodeScene System Mastery](https://codescene.io/projects/86238/status-badges/system-mastery)](https://codescene.io/projects/86238)

The persistence layer of Stratastax: a small, typed API to read, search and write entities, and a
reactive PostgreSQL implementation of it on the
[Vert.x reactive Postgres client](https://vertx.io/docs/vertx-pg-client/java/).

Every statement is literal SQL. The access classes and mappers of an entity are normally generated
by [stratastax-codegen](https://github.com/buijs-dev/stratastax-codegen) from annotated entities,
with all the entity's SQL in one `const val` object you can read in the build directory. This
library does the generic part: transactions, running statements and binding their parameters,
reading rows, rendering the filter, sort and keyset seek of a search, and explaining a write that
matched nothing.

## What you get
- **Reads and search** - `findById`, paged `findAll` with filters, sorting and keyset (cursor)
  paging, `count`, projections and `SELECT ... FOR UPDATE SKIP LOCKED` work queues. Related
  entities are read in the same query: one-to-one relations through joins, one-to-many relations
  as JSON arrays so no N+1.
- **Writes with relations** - create, update and delete an entity together with its one-to-one
  references, owned rows, many-to-many links and (nested) one-to-many children, every write in one
  transaction.
- **Batches** - `createAll`, `updateAll` and `deleteAll` write many entities with one statement per
  step, and report the outcome of every input on its own.
- **Optimistic locking and soft delete** - per entity, applied consistently to every write.
- **Transactions** - `UnitOfWork.transaction { }` with propagation, savepoints, isolation levels
  and read-only transactions, carried by the coroutine.
- **CSV import** - map CSV rows with the same `RowMapper` that maps database rows.
- **A test kit** - assert on the queries your code runs: query counts, N+1 detection, query plans,
  transactions and snapshots, against a real PostgreSQL in Testcontainers.

## Modules

| Module                 | What it contains                                                                                                                                                                                 | Depends on                                        |
|------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------|
| `persistence-core`     | The API (`ReadAccess`, `CreateAccess`, `UpdateAccess`, `DeleteAccess`, `UnitOfWork`, `BatchResult`, exceptions) and the SPI (`RowContext`, `RowMapper`, `QueryExecutionListener`). No framework. | `search-core`                                     |
| `persistence-reactive` | The Vert.x implementation: executors, transactions, search rendering (`SearchSql`) and the write kit generated code uses. Database-neutral: SQL syntax comes from a `PersistenceDialect`.        | `persistence-core`, Vert.x Mutiny SQL client      |
| `persistence-postgres` | `PostgresDialect`: the PostgreSQL dialect. Another database is a module like it, which can live in another repository; see [docs/dialects.md](docs/dialects.md).                                 | `persistence-reactive`, Vert.x Mutiny Postgres client |
| `persistence-csv`      | `CsvRowReader`: CSV rows through `RowContext`/`RowMapper`.                                                                                                                                       | `persistence-core`, commons-csv, Jackson 3        |
| `persistence-test`     | `PersistenceTestHarness`, query assertions, query plans, snapshots and `PostgresTestDatabase`. Test scope only.                                                                                  | `persistence-core`, Testcontainers, Jackson 3     |

Every module separates what you call (`api`), what you implement or configure (`spi`) and its own
internals (`internal`, not visible outside the module). See [docs/architecture.md](docs/architecture.md).

## Installation

```kotlin
dependencies {
    implementation("dev.buijs.stratastax:persistence-core:<version>")
    implementation("dev.buijs.stratastax:persistence-postgres:<version>") // brings persistence-reactive
    implementation("dev.buijs.stratastax:persistence-csv:<version>") // when importing CSV

    testImplementation("dev.buijs.stratastax:persistence-test:<version>")
}
```

The Vert.x and PostgreSQL driver versions follow Quarkus 3.38. A Quarkus application aligns them
with its own `enforcedPlatform(quarkus-bom)`; nothing else is needed.

## Quick start
Generated code looks like [this](docs/code-generation.md). By hand, the same building blocks:

```kotlin
// 1. Infrastructure, once per application.
val transactional = VertxTransactionalExecutor(pool, PostgresDialect) // pool: io.vertx.mutiny.sqlclient.Pool
val queries = transactional.ambientQueryExecutor()          // joins the caller's transaction

// 2. The statements of a `game`, and how its row maps.
@Language("PostgreSQL")
const val INSERT_GAME = "INSERT INTO game (public_id, name) VALUES ($1::uuid, $2::varchar) RETURNING public_id AS id, name"

data class Game(val id: UUID, val name: String)
val gameMapper = RowMapper { row -> Game(row.uuid("id"), row.string("name")) }

// 3. How a search on it is rendered: the fixed SQL, and per field where a condition and a sort go.
val gameSearch =
    SearchSql(
        dialect = PostgresDialect,
        select = "SELECT game.public_id AS id, game.name AS name FROM game",
        countSelect = "SELECT count(*) AS count FROM game",
        fields =
            mapOf(
                "id" to SearchField(SqlType.UUID, SearchTarget.Column("game.public_id"), sortColumn = "game.public_id", neverNull = true),
                "name" to SearchField(SqlType.VARCHAR, SearchTarget.Column("game.name"), sortColumn = "game.name", neverNull = true),
            ),
    )

// 4. Use them - inside one transaction when it has to be all or nothing.
val unitOfWork: UnitOfWork = transactional
val catan = unitOfWork.transaction {
    queries.queryFirst(INSERT_GAME, listOf(UUID.randomUUID(), "Catan"))?.let(gameMapper::map)
}
val idField = EntityField("id", EntityFieldType.UUID, sortable = true, filterable = true)
val nameField = EntityField("name", EntityFieldType.STRING, sortable = true, filterable = true)
val search = SearchQuery(limit = 20, sort = Sort(listOf(nameField.asc(), idField.asc())), filter = null, cursor = null)
val page: Page<Game> = queries.readPage(gameSearch.pageQuery(search), search, gameMapper)
```

## Documentation

Start with the [documentation overview](docs/README.md); it suggests a reading order.

| Topic                                                            |                                                                                |
|------------------------------------------------------------------|--------------------------------------------------------------------------------|
| [Architecture](docs/architecture.md)                             | The modules, and how a read and a write travel through them                    |
| [Why literal SQL](docs/sql-first-design.md)                      | The motivation behind the design, and its trade-offs                           |
| [Reading and search](docs/reading-and-search.md)                 | Relations, filters, sorting, cursor paging, projections, locking               |
| [Writes and relations](docs/writes-and-relations.md)             | Create, update and delete with references, links and child rows                |
| [Batch operations](docs/batch-operations.md)                     | `createAll`, `updateAll`, `deleteAll` and a result per input                   |
| [Versioning and soft delete](docs/versioning-and-soft-delete.md) | Optimistic locking, soft delete and what happens to related rows               |
| [Transactions](docs/transactions.md)                             | `UnitOfWork`, savepoints, options and rules of thumb                           |
| [CSV import](docs/csv-import.md)                                 | Reading CSV files with the same row mappers                                    |
| [Testing](docs/testing.md)                                       | Asserting on the SQL your code runs: counts, N+1, plans, snapshots             |
| [Code generation](docs/code-generation.md)                       | What generated code looks like and the rules it relies on                      |
| [SQL dialects](docs/dialects.md)                                 | What is PostgreSQL-specific, and what another database needs                   |

## Further reading
The Stratastax documentation is on [docs.stratastax.dev](https://docs.stratastax.dev/). 
Its page there is [Persistence](https://docs.stratastax.dev/technical/components/persistence/); see also the [persistence guidelines](https://docs.stratastax.dev/development/05-persistence/).

## Related Stratastax projects
- [stratastax-entity](https://github.com/buijs-dev/stratastax-entity) - the `@Persistence*` annotations this library implements
- [stratastax-codegen](https://github.com/buijs-dev/stratastax-codegen) - generates the SQL, row mappers and access classes on top of this library
- [stratastax-search › search-core](https://github.com/buijs-dev/stratastax-search/tree/main/search-core) - the search model (`SearchQuery`, filters, cursors) this library translates to SQL
- [stratastax-ambient-context](https://github.com/buijs-dev/stratastax-ambient-context) - context propagation across reactive boundaries
- [stratastax-quarkus › quarkus-starter](https://github.com/buijs-dev/stratastax-quarkus/tree/main/quarkus-starter) - wires this library into a Quarkus application
- [stratastax-style](https://github.com/buijs-dev/stratastax-style) - the Kotlin code style this library is formatted with
