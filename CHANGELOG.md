# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

First release of Stratastax Persistence: the generic persistence API used throughout Stratastax.

- `persistence-core`: the persistence API (`UnitOfWork`, `QueryExecution`, exceptions) and the
  `ReadAccess`/`CreateAccess`/`UpdateAccess`/`DeleteAccess`/`RowMapper` SPI.
- `persistence-csv`: commons-csv implementation of the CSV row-mapping API (`CsvRowReader`, `CsvRowMapper`).
- `persistence-reactive`: jOOQ/Vert.x reactive Postgres implementation (`VertxQueryExecutor`,
  `VertxTransactionalExecutor`, the `Jooq*Access` classes and the search query translator).
- `persistence-test`: test harness for asserting on the queries generated persistence code runs
  against PostgreSQL (`PersistenceTestHarness`, `PostgresTestDatabase`, query plan analysis).

See [README.md](https://github.com/buijs-dev/stratastax-persistence/blob/main/README.md) for examples.
