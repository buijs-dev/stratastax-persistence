# Reading and search

This page covers getting data out: a single entity, a page of search results, a projection with
fewer fields, and rows locked for a work queue.

```kotlin
val playset: Playset? = playsets.findById(id)
val page: Page<Playset> = playsets.findAll(search)
val total: Long = playsets.count(search)
```

## One query per read, relations included

An entity is read with a single `SELECT` that also brings its relations along. Code generation
writes it as two fragments, `COLUMNS` and `FROM`:

```kotlin
internal object PlaysetSql {

    const val COLUMNS: String = """
        playset.public_id AS id,
        playset.display_name AS name,
        game.display_name AS gameName,
        COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'expansionsId', expansions_m.public_id,
                'expansionsName', expansions_m.display_name
            ) ORDER BY expansions_m.id)
            FROM playset_expansion expansions_mj
            JOIN expansion expansions_m ON expansions_mj.expansion_id = expansions_m.id
            WHERE playset.id = expansions_mj.playset_id
        ), '[]'::jsonb) AS expansions"""

    const val FROM: String = """
        playset
        JOIN game ON playset.game_id = game.id"""

    @Language("PostgreSQL")
    const val SELECT: String = "SELECT $COLUMNS\nFROM $FROM"
}
```

How each kind of relation is read:

| Relation     | Read as                                                                                          |
|--------------|--------------------------------------------------------------------------------------------------|
| one-to-one   | a join. A `LEFT JOIN` when the relation is optional, so a missing one leaves its columns `NULL`. |
| one-to-many  | one JSON array column, named after the relation                                                  |
| many-to-many | the same, through the link table                                                                 |

Because related rows come along in the same statement, a page of 50 playsets is one query, not 51.
One-to-many relations nest: a child's array holds the arrays of its own children. Every related
table gets an alias, so a relation to the same table (a category with a parent category) works too.

## Row mappers

Every column has an alias that matches a field name. A `RowMapper` reads the row by those names:

```kotlin
val playsetMapper =
    RowMapper { row ->
        Playset(
            id = row.uuid("id"),
            name = row.string("name"),
            gameName = row.stringOrNull("gameName"),
            expansions = row.objectList("expansions").map { Expansion(it.uuid("expansionsId"), it.string("expansionsName")) },
        )
    }
```

`RowContext` has the same four accessors for every type:

| Accessor                            | Returns                          |
|-------------------------------------|----------------------------------|
| `uuid("id")`                        | the value; fails on `NULL`       |
| `uuidOrNull("id")`                  | the value or `null`              |
| `uuidList("ids")`, `uuidSet("ids")` | an array column as a list or set |

Types: `string`, `uuid`, `integer`, `long`, `decimal`, `boolean`, `localDate`, `offsetDateTime`.
For JSON there are `map(name)` and `objectList(name)`, which reads a JSON array as nested rows.

A typo in a column name never silently reads as `null`: it throws `UnknownColumnException`.

## Searching

A search is a `SearchQuery` from `search-core`: a filter, a sort, a limit and optionally a cursor.
Usually the search layer builds it from an HTTP request. The access class turns it into SQL with
the entity's `SearchSql`:

```kotlin
override suspend fun findAll(search: SearchQuery): Page<Playset> =
    queryExecutor.readPage(PlaysetEntityModel.searchSql.pageQuery(search), search, rowMapper)
```

`SearchSql` is generated. It holds the fixed `SELECT`, and for each searchable field the column a
filter compares and a sort orders by:

```kotlin
val searchSql =
    SearchSql(
        dialect = PostgresDialect,
        select = PlaysetSql.SELECT,
        countSelect = PlaysetSql.COUNT,
        fields =
            mapOf(
                "id" to SearchField(SqlType.UUID, SearchTarget.Column("playset.public_id"), sortColumn = "playset.public_id", neverNull = true),
                "name" to SearchField(SqlType.VARCHAR, SearchTarget.Column("playset.display_name"), sortColumn = "playset.display_name"),
            ),
        defaultConditions = listOf("playset.deleted_at IS NULL"),
        lockClause = "FOR UPDATE OF playset",
    )
```

A search for `name ILIKE 'fri%'`, sorted by name and then id, 20 per page, then renders as:

```sql
SELECT ... FROM playset JOIN game ON ...
WHERE playset.deleted_at IS NULL
  AND playset.display_name ILIKE $1::varchar
ORDER BY playset.display_name ASC NULLS LAST, playset.public_id ASC
LIMIT 21
```

One extra row is read to know whether there is a next page.

### Filters

Supported operators: `=`, `<>`, `<`, `<=`, `>`, `>=`, `LIKE`, `ILIKE`, `IN`, `OUT`, combined with
`AND` and `OR`.

- **`NULL` is a value of its own.** `field = null` becomes `IS NULL`; `IN (a, null)` matches `a`
  and `NULL`.
- **Filtering on a related field** becomes an `EXISTS (...)` along the relation. A playset with
  three matching notes is still returned once.
- **Lists of ids** bind as one array parameter (`= ANY($1::uuid[])`), however many values there are,
  so the statement text stays the same.

### Sorting

`NULL` sorts as the largest value (`ASC NULLS LAST`, `DESC NULLS FIRST`). You can sort on
one-to-one relations, but not on fields of a one-to-many relation: a playset has no single note
name to sort by.

### Cursor paging

Offset paging gets slower with every page and skips or repeats rows when data changes in between.
Keyset paging avoids both: you pass the cursor of the last row you saw, and the next page starts
right after it. Sort on a unique field last (usually the id), so every row has its own place in
the order.

When every sort field is `neverNull` and sorted in the same direction, the seek is one row
comparison that an index on those columns serves directly:

```sql
WHERE (playset.display_name, playset.public_id) > ($1::varchar, $2::uuid)
```

Use `CursorDirection.AFTER` for the next page and `BEFORE` for the previous one.

### Always applied: default conditions

`defaultConditions` apply to every read: searches, `findById`, and reading a row back after a
write. This is how a soft-deleted row stays invisible everywhere.

### Restricting what a caller sees: scopes

A scope is a filter on top of the caller's own filter, for example "only my own playsets":

```kotlin
val mine = playsets.findAll(search, ownerIdField eq currentUserId)
val total = playsets.count(search, ownerIdField eq currentUserId)
```

The scope is not part of the `SearchQuery`, so the cursors built for a page only carry the
caller's own filter and sort. A client that sends them back with the next cursor gets the next page
of the same search, and the scope is applied again on every request.

### When a search is invalid

A field the entity does not have throws `UnknownFieldException`. A sort, cursor or value that does
not fit throws `SearchException`. Both are thrown before any SQL runs.

## Projections

Not every screen needs every field. A projection, such as `MatchRef` with just an id and a name,
gets a `SELECT` of its own with only its columns and the joins it needs:

```kotlin
val refs: Page<MatchRef> = matches.findAllMatchRef { limit = 20 }
val all: List<MatchRef> = matches.findAllMatchRefList { /* filter */ }   // no limit, no cursor
```

## Work queues: `findAllForUpdate`

Several workers that pick up jobs from the same table must not pick the same row.
`findAllForUpdate` locks the rows it returns and skips rows another worker already locked:

```kotlin
unitOfWork.transaction {
    val claimed = jobs.findAllForUpdate(nextBatch)   // ... FOR UPDATE OF job SKIP LOCKED
    claimed.forEach { process(it) }
}                                                    // locks released on commit
```

Pass `skipLocked = false` to wait for locked rows instead. It must run inside a transaction:
outside one, the locks would be released immediately, so it throws.

## Queries of your own

A hand-written query can reuse the generated fragments and the generated row mapper, so it stays
in sync with the entity:

```kotlin
@Language("PostgreSQL")
private const val FIND_BY_PROVIDER = """
    SELECT ${UserSql.COLUMNS}
    FROM ${UserSql.FROM}
    JOIN app_user_identity identity ON app_user.id = identity.user_id
    WHERE identity.provider = $1::varchar AND identity.provider_user_id = $2::varchar"""

val user = executor.queryFirst(FIND_BY_PROVIDER, listOf(provider, providerUserId))?.let(userMapper::map)
```

Kotlin's `${...}` composes the fragments at compile time, while `$1` stays a literal placeholder.

---

Next: [Writes and relations](writes-and-relations.md)
