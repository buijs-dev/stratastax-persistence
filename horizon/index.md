The persistence layer of Stratastax: a small, typed API to read, search and write entities, and a
reactive PostgreSQL implementation of it on the Vert.x reactive Postgres client.

What sets it apart from an ORM: **every statement is literal SQL you can read**.
[Codegen](/technical/components/codegen/) writes the SQL of each entity into one Kotlin file in the
build directory, together with the mappers and access classes that run it. Nothing is assembled at
runtime, except the filter, sort and paging of a search. You can also write everything by hand.

```kotlin
// what a service sees
val playset: Playset? = playsets.findById(id)
val page: Page<Playset> = playsets.findAll(search)

// what actually runs: a constant in the build directory
const val FIND_BY_ID: String = """
    SELECT $COLUMNS
    FROM $FROM
    WHERE playset.public_id = $1::uuid AND playset.deleted_at IS NULL"""
```

- Repository: [stratastax-persistence](https://github.com/buijs-dev/stratastax-persistence) 
- Guides: [docs](https://docs.stratastax.dev/technical/).

## Why literal SQL
Generating the SQL makes it visible. Open an entity's `Sql` object and you see exactly what the
database sees, checked by the IDE against the schema. Every statement has the same text on every
call, so PostgreSQL prepares it once, and the test kit can compare it with a recorded snapshot. A
failed write is a stack trace through plain code, not through a query builder.

## What it does

- **Reads and search.** Load an entity by id, or search with filters, sorting and cursor paging.
  Related entities come along in the same query, so a page of 50 rows is one query, not 51.
- **Writes with relations.** Create, update and delete an entity together with the rows it
  references, owns, links to or contains, all in one transaction.
- **Batches.** Write many entities in a few round trips, with a separate outcome for every input,
  so one bad row does not spoil an import.
- **Optimistic locking and soft delete.** Switched on per entity and applied to every write, so
  two users never silently overwrite each other's changes and deleted rows can be restored.
- **Transactions.** Group calls with `unitOfWork.transaction { }`. A failing call inside it only
  undoes its own work, and the transaction travels with the coroutine.
- **CSV import.** Read CSV files with the same row mappers as database rows.
- **A test kit.** Assert on the SQL your code runs against a real PostgreSQL: query counts, N+1,
  query plans, transactions and snapshots.

```kotlin
unitOfWork.transaction {
    val playset = playsets.create(command)
    notes.create(CreateNote(playset.id, "first"))
}   // commit, or rollback when the block throws
```

## Modules

| Module                 | Use it for                                                                                                                                      |
|------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| `persistence-core`     | The contracts a service depends on (`ReadAccess`, `CreateAccess`, `UpdateAccess`, `DeleteAccess`, `UnitOfWork`); no database code, no framework |
| `persistence-reactive` | Running the SQL on PostgreSQL with Vert.x: transactions, search rendering, and the helpers generated code uses                                  |
| `persistence-csv`      | Importing CSV files                                                                                                                             |
| `persistence-test`     | Testing persistence code against PostgreSQL in Testcontainers (test scope)                                                                      |

A service only needs `persistence-core`; the application's wiring and the generated code use
`persistence-reactive`. The rules for using it are in [Persistence](/development/05-persistence/).
