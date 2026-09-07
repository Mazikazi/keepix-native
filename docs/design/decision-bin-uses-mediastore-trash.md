# Decision: the bin moves to MediaStore trash

Decided 2026-09-07, during the neumorphic restyle. Lands with step 4 (the deck
rewrite), not before — see "When" below.

## What ships today

A bin swipe writes a `bin_items` row and **does not touch the file**. The photo
stays in MediaStore, in the device gallery, and in Google Photos, occupying the
same bytes. Keeping it out of the deck is done in app code: `loadMedia` reads
`binMediaIds`/`keptMediaIds` into in-memory `Set<Long>`s and `fetchBatch`
re-reads and filters them on every page.

`SessionCleanupWorker` only ever *marks* expired rows `pendingDeletion`; its own
doc says it "never touches files and never drops a row". Permanent removal needs
the user to be in the app and confirm a `createDeleteRequest` dialog.

## The problem

A user bins 500 photos and all 500 are still in their gallery. The app's central
promise — the item is binned, with N days to change your mind — is not delivered
at the moment the promise is made.

Retention is also a *prompt* schedule rather than a deletion guarantee: if the
user never reopens the app, expired items live indefinitely.

## Decision

Bin swipes call `MediaStore.createTrashRequest(uris, true)`. The Room table stays
as metadata — day badges, session grouping, restore UX — but MediaStore owns
whether the item is visible.

## What this does and does not buy

Buys:

- The item disappears from the gallery and Google Photos at swipe time, which is
  what the design says happens.
- An OS backstop: trashed items are permanently removed at ~30 days even if the
  user never returns.
- Provider-side exclusion (`QUERY_ARG_MATCH_TRASHED`) for binned items, instead
  of an in-memory id set that grows with the library. `fetchBatch` currently
  loops pages until it accumulates enough survivors; its own comment names the
  "9,000 of 10,000 already binned" case.
- It makes the 30-day retention cap real. Addendum A1 capped the wheel at 30
  *because* "MediaStore's trash expires at ~30 days" and to keep the bin "a thin
  view over the system trash" — neither of which is true of an app-owned bin over
  untouched files. Today's combination, an app-owned bin carrying a cap that only
  makes sense for system trash, is the incoherent option.

Does **not** buy:

- Storage reclaim at bin time. Trashed bytes stay on disk until a permanent
  delete. The existing delete-at-expiry pipeline is still needed, because the
  user's retention window (7 days by default) is shorter than MediaStore's ~30.
  Trash is additive, not a replacement.

Costs:

- A hard 30-day ceiling on retention.
- Restore becomes `createTrashRequest(uris, false)` — its own consent dialog.
- Existing `bin_items` rows point at untrashed files and need a migration or a
  grandfathering path.

## When

Step 4, with the deck rewrite. Two reasons:

1. The trash request's trigger is the undo window. Handoff §3.3 has bin swipes
   commit lazily when the 5s window closes, which is exactly what lets
   consecutive bin swipes share one system dialog instead of prompting per photo.
   That window does not exist until step 4.
2. The consent plumbing in `MainActivity` — the `systemDialogInFlight` gate, the
   in-flight/run id pair, the chunked `MediaUriFilter` pass, the cancel-rearm
   rule — is delicate and already shared by the delete and favourite flows.
   Adding a third parallel flow to it now would mean writing that carefully into
   a screen step 4 replaces.

Adding `QUERY_ARG_MATCH_TRASHED = MATCH_EXCLUDE` ahead of time would be a no-op:
that is already MediaStore's default. It becomes worth stating explicitly when
the bin screen needs `MATCH_ONLY`.
