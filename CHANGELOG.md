# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
First release of Stratastax Persistence: the generic persistence layer used throughout Stratastax.
Repositories are generated as plain, literal SQL; this library supplies the runtime they run on.

- **A storage-independent API** (`persistence-core`). Services read, create, update and delete
  through small access interfaces and group writes in a unit of work, without knowing which
  database or driver sits underneath. Failures surface as typed exceptions that carry their
  details, and the SPI lets a backend map rows and report executed queries.
- **Reactive execution** (`persistence-reactive`). Runs the generated SQL on Vert.x, inside or
  outside a transaction, and sends batches in a single round trip. Common Kotlin types
  (JSON maps, URIs, enums, instants) are bound without extra code.
- **Pluggable SQL dialects.** Everything database-specific - transaction statements, placeholders,
  the syntax of a search and parameter binding - sits behind `PersistenceDialect` and
  `SearchSyntax`, so a dialect can be written in a module of its own, also outside this repository.
  PostgreSQL is `PostgresDialect` in `persistence-postgres`.
- **Search with keyset pagination.** Filtering, sorting, cursor-based paging and row locking are
  composed from generated SQL fragments, so search queries stay predictable and index-friendly.
- **Safe writes.** Shared building blocks for the checks every write needs: optimistic versioning,
  verifying that referenced rows exist, detecting rows that were not written, and syncing child
  collections. Generated code relies on these instead of repeating them per entity; see
  `docs/code-generation.md`.
- **CSV import** (`persistence-csv`). Reads CSV files through the same row mappers as the database,
  so seed and import data reuse the existing mapping, including list and JSON cells.
- **Query observability** (`persistence-observability`). Every statement becomes an OpenTelemetry
  span under the HTTP request that caused it, grouped per transaction and placed at its exact
  start and end, and each request gets a query profile (count, database time, probable N+1).
  Bind values are never exported unless a renderer is configured. Statements now carry the
  wall-clock time they were sent (`QueryExecution.startedAt`).
- **Test support** (`persistence-test`, `persistence-postgres-test`). A database-independent
  harness to assert on executed queries, query plans and transactions, plus scenarios and
  snapshots for repository tests; the PostgreSQL module adds a throwaway database and the
  `EXPLAIN`-based plan analyzer.
- **Documentation** under `docs/`, covering architecture, the SQL-first design, code generation,
  reading and search, writes and relations, batches, transactions, versioning and soft delete,
  dialects, CSV import, testing and observability.

### Notes
- Explicit API mode and an API dump (`./gradlew updateKotlinAbi`) in every module.
- JSON is handled with Jackson 3 (`tools.jackson`); no Jackson 2 runtime comes in through this
  library, except Vert.x 4's own `jackson-core` in `persistence-reactive` and `persistence-postgres`.
- The Vert.x, Mutiny and PostgreSQL driver versions are pinned to the ones of Quarkus 3.38.

See [README.md](https://github.com/buijs-dev/stratastax-persistence/blob/main/README.md) and
[docs/](https://github.com/buijs-dev/stratastax-persistence/tree/main/docs) for examples.
