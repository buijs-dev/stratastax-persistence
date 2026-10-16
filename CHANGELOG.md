# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

First release of Stratastax Persistence: the generic persistence API used throughout Stratastax.

- `persistence-core`: the API (`ReadAccess`, `CreateAccess`, `UpdateAccess`, `DeleteAccess`,
  `UnitOfWork`, `BatchResult`, exceptions with their details as properties) and the SPI
  (`RowContext` with `localDate`, `RowMapper`, `CreateMapper`, `UpdateMapper`,
  `QueryExecutionListener`, `QueryExecution`).
- `persistence-reactive`: the jOOQ/Vert.x PostgreSQL implementation - `VertxQueryExecutor`,
  `VertxTransactionalExecutor`, `AmbientQueryExecutor`, `PostgresDialect` and the
  `PersistenceDialect` SPI, `JooqEntityBinding`, the `Jooq*Access` building blocks with batched
  writes for every relation kind, and `JooqSearchQueryTranslator`.
- `persistence-csv`: `CsvRowReader` - CSV rows through `RowContext` and `RowMapper`, with list
  and JSON cells.
- `persistence-test`: `PersistenceTestHarness`, query, plan and transaction assertions, scenarios,
  snapshots, `PostgresQueryPlanAnalyzer` and `PostgresTestDatabase`.
- Documentation under `docs/`.

### Notes

- Explicit API mode and an API dump (`./gradlew updateKotlinAbi`) in every module.
- JSON is handled with Jackson 3 (`tools.jackson`); no Jackson 2 runtime comes in through this
  library, except Vert.x 4's own `jackson-core` in `persistence-reactive`.
- The Vert.x, Mutiny and PostgreSQL driver versions are pinned to the ones of Quarkus 3.38; the
  library no longer imports the Quarkus BOM.

See [README.md](https://github.com/buijs-dev/stratastax-persistence/blob/main/README.md) and
[docs/](https://github.com/buijs-dev/stratastax-persistence/tree/main/docs) for examples.
