# Versioning and soft delete

Both are declared on the entity (its version and soft-delete fields) and written into its
statements as literal SQL by [code generation](code-generation.md).

## Optimistic locking

With a version column:

- a new row starts at version `1`, written as a literal in the `INSERT`;
- every update and delete requires the caller's expected version - a missing or invalid one fails
  with `IllegalArgumentException` before any SQL runs (`rejectedInputs`);
- the statement matches the row on id **and** version, and sets `version = version + 1` in the same
  statement, so two writes based on the same version can never both succeed;
- a write that matched nothing is explained with one extra lookup (`diagnoseMiss`): a stale version
  is an `OptimisticLockException`, a missing row an `EntityNotFoundException`.

`SqlVersion` names the column and the type an expected version is converted to: an `If-Match`
header of `"3"` is a valid `bigint` version.

Without a version column writes are last-write-wins and the expected version is ignored.

## Soft delete

With a soft-delete column:

- `delete` runs one statement that sets the column instead of deleting the row, and increments the
  version:

  ```sql
  UPDATE playset
  SET deleted_at = now(), version = version + 1
  WHERE public_id = $1::uuid AND version = $2::bigint AND deleted_at IS NULL
  RETURNING public_id
  ```

- a soft-deleted row counts as gone: updating or deleting it again fails with
  `EntityNotFoundException`, and nothing about it changes;
- every read hides it: the column is a default condition of the entity's `SearchSql`
  (`playset.deleted_at IS NULL`), and `FIND_BY_ID` and `SELECT_BY_IDS` carry it as well.

## What happens to the relations

| Relation | Row deleted | Row soft-deleted |
|---|---|---|
| many-to-many links | deleted (and the linked targets, when the entity owns them) | kept - the row can be restored |
| one-to-many children | deleted, with their own children and owned rows | kept |
| owned one-to-one rows | deleted after the row | kept (the row still references it) |

The same rules apply one level down: a child removed by an update takes its own children and owned
rows with it.

## Hand-written soft deletes

Child rows that are soft-deleted together with their parent - the participants of a match - are a
statement next to the generated delete, in the same transaction; see the last example of
[Writes and relations](writes-and-relations.md). The write kit's `childDiff` also restores a
soft-deleted child when an element names it again, for an access class that keeps soft-deleted
children.
