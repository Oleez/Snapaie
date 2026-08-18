# Ported from the AI Explainer extension

This document records what was brought over from the **AI Explainer** Chrome
extension (`ai-explainer-extension`) into snapaie, and why.

The two products share a feature family — Forge Recall, Chat with AE, the
writing assistant, narration, history — and snapaie's design system already
credits the extension (`DesignTokens.kt` maps the extension's `:root` CSS
variables). What snapaie was still missing was the extension's *feedback and
history* layer.

Two things were ported. Both were re-implemented natively in Compose; no code
was copied, because a vanilla-JS popup and a Compose app share no runtime.

---

## 1. Notification centre + toasts

**Ported from:** `popup.js` → `pushPersistentNotification()`,
`showNotification()`, `renderNotifications()`, `flashNotificationButton()`,
`promptUpgradePricing()`.

**Why:** snapaie had no acknowledgement of *anything*. Deleting a scan, exporting
a file and finishing a multi-GB model download were all silent. The extension
solved this with two surfaces — a persistent bell list and transient inline
messages — and both translate directly to a phone.

### What was built

| Piece | Behaviour |
| --- | --- |
| `NotificationCenter` | Persistent list with unread count, backed by DataStore |
| Dedupe window | Identical title + message inside 5 minutes is dropped (extension's `NOTIFICATION_DEDUPE_WINDOW_MS`) |
| Cap | Newest 100 kept (extension's cap) |
| Auto-clear | `Update` notices self-mark-read after 10s so the badge never nags; `Tip` / `Promo` / `Achievement` stay until read (extension's rule, exactly) |
| `NotificationBell` | Bell + unread badge; a short scale pulse replaces the extension's colour flash, which reads better on a touch target |
| `NotificationCenterSheet` | Modal bottom sheet: unread dot, relative timestamps, per-item dismiss, "Mark all read", "Clear" |
| `SnapToastController` | App-wide transient channel; one `SnackbarHost` in the shell so a snackbar appears above the nav bar on every route |

### Deliberate difference from the extension

The extension's notification CTA opened an external URL (`PRICING_URL`).
**Here a CTA is an in-app route only** (`ctaRoute`, e.g. `Routes.scanDetail(12)`),
so the notification centre cannot break the "the only network request is the
model download" promise.

### Where notifications now fire

- **Scan saved** → `Update`, CTA opens the scan.
- **Model ready** → `Update`, fired when a download transitions to ready. This is
  the case that most needed it: the download finishes long after the user has
  left the Snap tab.
- **Forge deck full (free tier)** → `Promo`, CTA opens Upgrade. This is the port
  of the extension's `promptUpgradePricing()`; the inline message it replaces
  vanished as soon as the screen changed.

### Where toasts now fire

- Note saved / note deleted (**with Undo**)
- Chat deleted
- Scan deleted (**with Undo**)
- Markdown export ready
- Bulk export blocked on free tier (**with a "See Pro" action**)

**Undo restores the row with its original primary key**, so a chat session that
points at a deleted scan keeps working after an undo.

---

## 2. Library search, filters and bulk export

**Ported from:** the extension's History panel — subtabs, search box, date
filter, and "export current session" / "export stored history".

**Why:** snapaie's Library had the subtabs but nothing else. Once you had more
than a screenful of scans there was no way to find one, and the only export was
a single scan at a time from the detail screen.

### What was built

- **Search** across everything worth searching, not just titles: book title,
  style, source text, concise meaning, core idea, author intent, simplified
  explanation, hidden meaning, insights, key quotes and vocabulary. Multiple
  words **narrow** the result (all terms must match) rather than widen it.
- **Time filter chips**: All time / Today / 7 days. "Today" is the current
  calendar day in the device timezone, not a rolling 24 hours.
- **Sort chips**: Newest / Oldest / Best compression. "Best compression" is
  hidden on the Chats and Notes tabs, where it has no meaning.
- **Live counts**: tab chips show totals (`Explanations 42`); the summary row
  shows `12 of 42 explanations` whenever a filter is narrowing.
- **Real empty states**: "no scans yet" and "nothing matches *your search*" are
  now different messages with different advice.
- **Filters persist** across app restarts (DataStore).
- **Bulk export** of Markdown or JSON, for scans, chats (full transcripts) and
  notes.

### Export exports what you are looking at

The export acts on the **filtered** list, not the whole table — search for one
book and you export only that book. JSON sits next to Markdown deliberately: the
point of an on-device app is that the user can take their data out without a
server round trip.

Bulk export stays **Pro**, matching the existing "Markdown/Obsidian export"
entitlement in the README. Free users get a toast with a "See Pro" action rather
than a dead button.

---

## What was deliberately *not* ported

- **Keyboard shortcuts** (`Ctrl+Shift+E/Y/Q/S`). There is no keyboard on a phone;
  the equivalent affordances here are the share sheet, the `ACTION_PROCESS_TEXT`
  selection entry point and the bottom nav, which already exist.
- **The extension's popup size system.** Android handles window sizing; snapaie
  already exposes a text-scale setting instead.
- **External-URL CTAs and any network-backed notification feed.** Incompatible
  with snapaie's offline guarantee.

---

## Files

### New

| File | Purpose |
| --- | --- |
| `domain/notifications/NotificationCenter.kt` | Notification model, kinds, dedupe/cap/auto-read store |
| `ui/notifications/SnapToast.kt` | App-wide transient toast channel + `LocalSnapToast` |
| `ui/notifications/NotificationCenterUi.kt` | `NotificationBell`, `NotificationCenterSheet`, relative-time formatting |
| `domain/library/LibraryFilter.kt` | Pure search / range / sort logic for all three Library tabs |
| `domain/share/LibraryExporter.kt` | Bulk Markdown + JSON export for scans, chats, notes |
| `test/domain/library/LibraryFilterTest.kt` | 8 tests covering search, ranges, sorts |

### Edited

| File | Change |
| --- | --- |
| `SnapAieApplication.kt` | `AppContainer` gains `notificationCenter` and `libraryExporter` |
| `data/preferences/AppPreferences.kt` | New keys: notification list JSON, library range, library sort; new `LibraryFilters` model |
| `ui/SnapAieViewModel.kt` | Exposes notifications + unread count; pushes the scan-saved and model-ready notifications; `deleteScan` now hands back an undo action |
| `ui/SnapAieApp.kt` | Hosts the snackbar and the notification sheet; provides `LocalSnapToast`; passes bell state to Snap and Library |
| `ui/scan/ScanScreens.kt` | Bell in the Snap header; toasts on export and delete-with-undo; `Promo` notification when the free Forge deck is full |
| `ui/library/LibraryScreen.kt` | Rewritten: search, filter/sort chips, counts, empty states, export menu, undoable note delete |

`MarkdownExporter` is untouched and still owns single-scan export;
`LibraryExporter` is the many-rows case.

---

## Verification

```powershell
.\gradlew.bat :app:compileDebugKotlin   # BUILD SUCCESSFUL
.\gradlew.bat :app:testDebugUnitTest    # BUILD SUCCESSFUL, LibraryFilterTest 8/8
.\gradlew.bat :app:assembleDebug        # BUILD SUCCESSFUL
```

No new warnings. The only warnings in the build are pre-existing
(`TRIM_MEMORY_RUNNING_LOW`, `LocalClipboardManager` deprecations).

Not yet exercised on a device — the flows worth checking by hand are the bell
badge after a scan, snackbar placement above the bottom nav, undo after deleting
a scan, and the export chooser.

---

## Worth doing next

- `NotificationCenter` has no unit test; it needs `AppPreferencesRepository`
  behind an interface first.
- Forge Recall streak and level-up milestones are the obvious next
  `Achievement` notifications — the XP ledger already computes them.
- `MarkdownExporter.exportAll()` is now dead code, superseded by
  `LibraryExporter.scansToMarkdown()`.
