# Stratastax Persistence documentation

Stratastax Persistence is the layer between a Stratastax service and its PostgreSQL database. A
service asks for an entity, a page of search results or a write; this library runs the SQL for it
inside the right transaction and hands back typed objects.

What makes it different from an ORM: **every statement is literal SQL that you can read**. Code
generation writes the SQL of each entity into one Kotlin file in the build directory. Nothing is
assembled at runtime, except the part of a search that depends on the request.

```kotlin
// what a service sees
val playset: Playset? = playsets.findById(id)
val page: Page<Playset> = playsets.findAll(search)
val created: Playset = playsets.create(command)

// what actually runs - a constant in the build directory
const val FIND_BY_ID: String = """
    SELECT $COLUMNS
    FROM $FROM
    WHERE playset.public_id = $1::uuid AND playset.deleted_at IS NULL"""
```

## Reading guide

Start with the first two pages for the big picture, then read whatever topic you need.

**The big picture**

1. [Architecture](architecture.md) - the modules, and how a read and a write travel through them.
2. [Why literal SQL](sql-first-design.md) - the motivation behind the design and its trade-offs.

**Using it**

3. [Reading and search](reading-and-search.md) - loading entities with their relations, filtering,
   sorting, paging and locking.
4. [Writes and relations](writes-and-relations.md) - create, update and delete, including related
   rows.
5. [Batch operations](batch-operations.md) - writing many entities at once, with a result per
   input.
6. [Versioning and soft delete](versioning-and-soft-delete.md) - optimistic locking, and deleting
   without losing data.
7. [Transactions](transactions.md) - grouping work into one atomic unit.
8. [CSV import](csv-import.md) - reading CSV files with the same mappers as database rows.
9. [Testing](testing.md) - asserting on the SQL your code runs.

**Reference**

10. [Code generation](code-generation.md) - what generated code looks like and what it relies on.
11. [SQL dialects](dialects.md) - what is PostgreSQL-specific, and what another database needs.

## The running example

Most examples use the `Playset` entity of the Stratastax engine: a named collection of a game and
its expansions that belongs to a user.

```
playset ──► game                 (required, referenced by public id)
        ──► app_user (owner)     (the current user)
        ◄─► expansion            (many-to-many, through playset_expansion)
```

Where a page needs child rows, it uses the engine's `Match` and its participants.
