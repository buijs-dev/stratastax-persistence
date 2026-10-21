# CSV import

Seed data, rankings from an external source, a spreadsheet a user uploads: data often arrives as
CSV. `CsvRowReader` reads it with a `RowMapper`, the same type that maps a database row. A mapper
you already have for an entity can import CSV without any extra code.

```kotlin
data class Ranking(
    val id: Int, 
    val name: String, 
    val year: Int, 
    val isExpansion: Boolean
)

val mapper =
    RowMapper { row ->
        Ranking(
            row.integer("id"), 
            row.string("name"),
            row.integer("yearPublished"), 
            row.boolean("isExpansion")
        )
    }

val result = File("rankings.csv").reader().use { CsvRowReader.read(it, mapper) }

result.imported   // the mapped rows, in file order
result.skipped    // the rows that could not be mapped, and why
```

```csv
id,name,year_published,is_expansion
13,Catan,1995,false
926,Seafarers,1997,true
```

The file is RFC 4180 CSV with a header row: what every spreadsheet exports.

## A bad row does not stop the import

A row the mapper cannot read is skipped and reported, and the import carries on:

```kotlin
result.skipped
// [CsvSkippedRow(record = 2, reason = "Column 'id' with value 'x' could not be read: ...")]
```

The record number counts data rows, starting at 1. To stop at the first bad row instead, set
`failOnInvalidRow = true`. The import then throws a `CsvImportException`.

## Column names are forgiving

Column names from the header are matched loosely: case is ignored, and so are underscores.
`year_published`, `yearPublished` and `YearPublished` are the same column, so a mapper written for
the database's camelCase aliases reads a snake_case CSV header as well.

When two header columns match the same name, the import is rejected with a `CsvImportException`,
rather than one silently winning.

## What a cell can hold

| Cell                                 | Read as                                                                    |
|--------------------------------------|----------------------------------------------------------------------------|
| empty or blank                       | `null`, or an empty list                                                   |
| `  42 `                              | `42`: values are trimmed first                                             |
| `true`, `false`, `1`, `0`            | booleans, in any case                                                      |
| `2026-01-02`, `2026-01-02T10:30:00Z` | `localDate`, `offsetDateTime` (ISO-8601)                                   |
| `"Catan, Azul"`                      | a list (`stringList`, `uuidList`, ...): split on the delimiter and trimmed |
| `"[""Catan, the game"", ""Azul""]"`  | a list as a JSON array, for elements that contain the delimiter            |
| `"{""players"": 4}"`                 | `map(name)`: a JSON object                                                 |
| `"[{""name"": ""Seafarers""}]"`      | `objectList(name)`: a JSON array of objects, each read as a nested row     |

A cell that contains the delimiter or quotes must be quoted, with every quote inside it doubled.

JSON cells are decoded with Jackson 3 into plain maps and lists, without reflection, so this also
works in a native image.

## Options

```kotlin
CsvRowReader.read(
    input,
    mapper,
    CsvReadOptions(
        listDelimiter = ';',                         // default ','
        columnNameNormalizer = { it.lowercase() },   // default: lowercase, without underscores
        failOnInvalidRow = true,                     // default false: skip and report
    ),
)
```

## Errors

Asking for a column the header does not have, or a value that is not of the requested type, throws
a `CsvImportException` that names the column and the value, with the parse error as its cause.
Inside a mapper, that means the row is skipped and reported, unless `failOnInvalidRow` is set.

---

Next: [Testing](testing.md)
