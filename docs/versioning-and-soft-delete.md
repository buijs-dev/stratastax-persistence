# Versioning and soft delete

Two features you switch on per entity, by giving it a version field and a soft-delete field. Code
generation then writes them into every statement of that entity, so they apply consistently: there
is no write path that forgets them.

## Optimistic locking

**The problem:** two users open the same record. Both edit it and save. Without protection, the
second save silently overwrites the first.

**The solution:** every row carries a version number. A write states which version it is based on,
and only succeeds if the row still has that version:

```kotlin
// Example for an entity named Playset
val playset = playsets.findById(id)                     // version 3
playsets.update(id, rename, expectedVersion = 3)        // ok, now version 4
playsets.update(id, recolor, expectedVersion = 3)       // OptimisticLockException: someone was first
```

In SQL, the check and the increment are one statement, so two writes based on the same version
can never both succeed:

```sql
UPDATE playset
SET ..., version = version + 1
WHERE public_id = $1::uuid
  AND version = $2::bigint
```

How it behaves:
- A new row starts at version `1`.
- An update or delete of a versioned entity **requires** an expected version. Without one, or with
  one that is not a number, it fails with `IllegalArgumentException` before any SQL runs.
- The expected version may be text, so an HTTP `If-Match: "3"` header can be passed on as it is.
- When the statement matches nothing, one extra query tells the two cases apart: the row is gone
  (`EntityNotFoundException`), or its version moved on (`OptimisticLockException`).

An entity without a version field is last-write-wins, and the expected version is ignored.

## Soft delete

**The problem:** deleting a row loses it for good, and with it the history other rows may still
refer to.

**The solution:** with a soft-delete column, `delete` marks the row instead of removing it:

```sql
UPDATE playset
SET deleted_at = now(), version = version + 1
WHERE public_id = $1::uuid AND version = $2::bigint AND deleted_at IS NULL
RETURNING public_id
```

From then on the row is gone as far as the application is concerned:

- **Every read hides it.** `deleted_at IS NULL` is a default condition of every search, and part
  of `findById` and of the read-back after a write.
- **Writes treat it as missing.** Updating or deleting it again fails with
  `EntityNotFoundException`, and nothing about it changes.

The row is still in the table, so it can be restored or inspected.

## What happens to related rows

| Related rows          | Hard delete                                                  | Soft delete                              |
|-----------------------|--------------------------------------------------------------|------------------------------------------|
| many-to-many links    | deleted (and the linked rows too, when the entity owns them) | kept, so the row can be restored         |
| one-to-many children  | deleted, with their own children and owned rows              | kept                                     |
| owned one-to-one rows | deleted after the row                                        | kept, since the row still refers to them |

The same rules apply one level down: a child removed by an update takes its own children and owned
rows with it.

Children that should be soft-deleted *together with* their parent, such as the participants of a
match, need a statement of their own next to the delete. See the last example of
[Writes and relations](writes-and-relations.md#writes-of-your-own).

A child that was soft-deleted and is named again in a later update is restored rather than inserted
a second time.

---

Next: [Transactions](transactions.md)
