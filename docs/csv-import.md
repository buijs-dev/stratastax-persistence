# CSV import

`CsvRowReader` reads an RFC 4180 CSV file with a header row and maps every data row with a
`RowMapper` - the same type that maps a database row. A mapper generated for an entity therefore
imports CSV too.

```kotlin
data class Ranking(val id: Int, val name: String, val year: Int, val isExpansion: Boolean)

val mapper =
    RowMapper { row ->
        Ranking(row.integer("id"), row.string("name"), row.integer("yearPublished"), row.boolean("isExpansion"))
    }

val result = File("rankings.csv").reader().use { CsvRowReader.read(it, mapper) }

result.imported          // the mapped rows, in file order
result.skipped           // CsvSkippedRow(record = 2, reason = "Column 'id' with value 'x' could not be read: ...")
```

## Cells

| Cell | Read as |
|---|---|
| empty or blank | `null` (and an empty list) |
| `  42 ` | values are trimmed before they are parsed |
| `true` / `false` / `1` / `0` | booleans (case-insensitive) |
| `2026-01-02`, `2026-01-02T10:30:00Z` | `localDate`, `offsetDateTime` (ISO-8601) |
| `"Catan, Azul"` | a list (`stringList`, `uuidList`, ...): split on the list delimiter, trimmed, empty elements dropped |
| `"[""Catan, the game"", ""Azul""]"` | a list given as a JSON array - for elements that contain the delimiter themselves |
| `"{""players"": 4}"` | `map(name)` - a JSON object |
| `"[{""name"": ""Seafarers""}]"` | `objectList(name)` - a JSON array of objects, each read as a nested row |

A cell containing the delimiter or quotes must be quoted, and a quote inside it doubled - that is
RFC 4180, which every spreadsheet writes.

## Column names

The names a mapper asks for are matched against the header through a normalizer - by default
case-insensitively and ignoring underscores, so `year_published`, `yearPublished` and
`YearPublished` are the same column. Two header columns that normalize to the same name are
rejected (`CsvImportException`), rather than one silently winning.

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

- A row the mapper cannot map is skipped and reported in `skipped` with its record number (the
  first data row is record 1) and the reason - or, with `failOnInvalidRow`, the import stops with a
  `CsvImportException`.
- Reading a column that is not in the header, or a value that is not of the requested type, throws
  `CsvImportException` naming the column and the value, with the parse error as its cause.

JSON cells are decoded with Jackson 3 into plain maps and lists - no reflection, so this also works
in a native image.
