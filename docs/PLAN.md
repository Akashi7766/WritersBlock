# WritersBlock — plan and status

Last updated: 2026-10-07

## Context

WritersBlock is a system-wide floating capture drawer over a plain-text notes tree. The drawer
(`service/OverlayService.kt`) was a hand-built `LinearLayout` tree holding a single `EditText`, and
CAPTURE (`service/CaptureActivity.kt`) screenshotted the screen, cropped a region, ran ML Kit OCR and
injected the resulting text into that editor — discarding the cropped bitmap.

Two gaps drove this work:

1. **The drawer was a dead end.** A single global scratch buffer (`prefs.overlayDraft`). SAVE invented
   a timestamped filename every time and dumped it in a "Quick Captures" folder. `AppPreferences.overlayActiveFilePath`
   was written and read nowhere, so the note you were actually working on was unreachable from the
   overlay.
2. **Captures lost the picture.** OCR text alone is a lossy, error-prone copy, and there was nowhere
   to keep the scanned image.

**Target outcome:** a note is one scrollable document mixing text and captured images, editable both
in the app and from the overlay. The drawer works in two modes — scratch (throwaway) and bound
(everything typed or scanned lands in a chosen note) — and shares one editor implementation with the
in-app editor.

## Settled decisions

| Decision | Choice |
|---|---|
| Note storage | One text file plus a sibling `<Name>.assets/` folder. Not a binary container — preserves grep-ability, snippets, portability. |
| Reading experience | Images render inline, in position, between text blocks. The `![...](...)` ref is storage, never shown. |
| Capture result | Inserts image block + OCR text beneath it. The text is an ordinary editable paragraph, so OCR errors are fixable in place. |
| Editing | Both in-app (`FileEditorScreen`) and from the overlay drawer. |
| Overlay UI | The whole drawer is a Compose `ComposeView` in the `WindowManager`, so the editor is written once and shared with the app. |
| Draft storage | Length-prefixed block codec under one pref key (`overlayPending`), not a JSON blob and not a note file. Unsaved work is not a note. |
| Sheets in the drawer | Drawn as siblings inside the drawer window. `Dialog` and `ModalBottomSheet` need a window token a service does not have. |

The on-disk format is specified separately in [NOTE-FORMAT.md](NOTE-FORMAT.md).

## Phase status

| Phase | State |
|---|---|
| 1 — Note format and repository | **Done, verified.** |
| 2 — Shared editor, adopted in the app | **Done. Unit-tested. Not device-verified.** |
| 3 — Compose drawer | **Done. The whole drawer is Compose and builds. Not device-verified.** |
| 4 — Modes and binding | **Done. Not device-verified.** |
| 5 — Capture keeps the image | **Done. Not device-verified.** |
| 6 — Cleanup and docs | **Done.** |
| 7 — The scan actually runs | **Done. Unit-tested. Not device-verified.** |
| 8 — Interface pass | **Done. Not device-verified.** |
| 9 — The capture stops being a picture of itself | **Done. The theme fix is verified in the compiled resource table. Not device-verified.** |
| 10 — Castle redesign and scan fixes | **Done. Verified on the emulator (API 36). Not verified on a physical device.** |

Everything below is verified to compile and to pass its unit tests (`assembleDebug`,
`assembleRelease`, `testDebugUnitTest`). Nothing is verified on a physical device — the emulator was
not used, so every runtime behaviour claim is a reading of the code, not an observation.

### Phase 1 — verified

`data/NoteFormat.kt`, `data/CaptureStore.kt`, extended `data/FileRepository.kt`,
`app/src/test/.../NoteFormatTest.kt` (32 tests).

Two real bugs were found when the tests were first run, both in `NoteFormat.kt`:

- **The parser never recognised a single image.** The target regex forbade whitespace
  (`[^)\s]+`), but the assets directory is named after the note, so a note called
  `Meeting notes.md` owns `Meeting notes.assets/…`. Every reference failed to match and fell through
  to a `Text` block, so notes rendered as raw syntax. Notably the *round-trip* tests all passed
  through this, because an unrecognised reference line round-trips as literal text — which is why the
  structure and snippet tests were the ones that caught it. The whitespace rule now applies to the
  file part after the first slash, not the whole target.
- **`assetsDirName` was wrong for legacy `.txt` files.** `substringBeforeLast('.')` mapped both
  `Notes.md` and an existing `Notes.txt` onto the same `Notes.assets`, silently sharing captures.

Both have regression tests. Deviation from the original plan: `CaptureStore.kt` was split out of
`FileRepository` so note-format logic stays free of `android.graphics` and unit-testable without
Robolectric.

### Phase 2 — done, unverified on device

- `ui/NoteDocument.kt` — presentation-only renderer. Text blocks are `BasicTextField`s when editable;
  image blocks resolve their ref against the note's assets dir and decode off the main thread, with a
  legible placeholder when a ref dangles or decodes to garbage. No load/save policy.
- `ui/FileEditorScreen.kt` — `textContent: String` swapped for a `SnapshotStateList<NoteBlock>`,
  serialized on save. The 30s auto-save, `BackHandler` guard, save-as dialog and footer stats are
  untouched and still apply.
- `app/src/test/.../NoteDocumentTest.kt` — 19 tests over the editing contract.

**Edits mutate one element in place rather than rebuilding the list.** The obvious implementation —
recompute the block list per keystroke and hand the whole thing back — changes every block's identity,
and since the lazy list keys on those identities, it recreates the `BasicTextField` and the caret
snaps to the start after every character. `replaceText` reassigns only the edited block. Related trap
that is also pinned by tests: an empty document must be seeded with one empty block up front,
otherwise the first keystroke is what creates the block it lands in, destroying the field being typed
into and closing the keyboard after one letter.

### Phase 3 — the drawer is now entirely Compose

The phase-3 spike (Compose in only the editor region, with a diagnostic readout) is gone. The whole
drawer is one composable, `ui/OverlayDrawer.kt`:
`BoxWithConstraints` → scrim, panel, sheets. `ui/OverlayDrawerContent.kt` is deleted.

- **Sliding.** `Animatable` drives a horizontal offset. `setDrawerOpen` swaps the window between
  `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN` with `alpha = 0` when parked and
  bare `FLAG_LAYOUT_IN_SCREEN` with `alpha = 1` when open. A fully transparent, untouchable window is
  still a window, so the drawer keeps its composition and its scroll position while it is away.
- **The offset is read in the layout and draw phases only.** `Modifier.offset { … }` defers its read
  to layout and the two scrims compute their alpha inside `drawBehind`. Reading `offsetX.value` in the
  composition would recompose the editor, the note lists and the picker on every frame of the slide.
- **Focus.** Bumped 260 ms after the slide starts, matching the 220 ms animation. Making a window
  focusable and asking it to raise the keyboard are two separate round trips to the window manager,
  and the second loses if it is sent in the same frame as the first.
- **Sheets are in-window.** `SaveSheet` and `NotePickerSheet` are drawn as `Box` siblings aligned
  `CenterEnd` and sized to the panel, with their own `Animatable` progress. They are not `Dialog`s:
  a service has no window token, so a dialog from the drawer throws `BadTokenException`.
- **The keyboard is drawn *above* an application overlay.** `TYPE_INPUT_METHOD` outranks
  `TYPE_APPLICATION_OVERLAY` in the window layer order, so an unresized drawer ends up with its
  composer behind the keyboard. `SOFT_INPUT_ADJUST_RESIZE` and `imePadding()` are both applied; if the
  window manager honours the resize the window shrinks and the inset is zero, so the two cannot
  double-count.

#### Activation: the edge swipe was dropped

The plan had a right-edge activation strip. It is not in the build, deliberately. A touchable strip
along the right edge sits exactly where gesture navigation's back gesture begins, and
`FLAG_NOT_TOUCHABLE` would make the strip undetectable — there is no version of it that both works and
does not fight the system. Swipability was delivered as:

- flick or drag the handle leftwards to open (the handle is a white pill with a grip, tap or drag to
  move, `fling` to open),
- drag the panel right to dismiss, with a velocity threshold as well as a distance one,
- tap the scrim to dismiss,
- an explicit **OPEN DRAWER** button on the dashboard, so the handle is never the only way in.

`AppPreferences.activationMode` — the stub with `MODE_FLOATING_HANDLE` / `MODE_EDGE_SWIPE` /
`MODE_NOTIFICATION_TILE` — is deleted rather than wired up. There is one activation mode and a
constant named for it was misleading.

### Phase 4 — scratch and bound modes

`AppPreferences.overlayActiveFilePath` is now the live mode flag: empty means scratch, non-empty means
bound. No new storage key. `boundPath` / `boundBlocks` in the service drive the pattern; the bound
note's blocks are parsed read-only above the composer so you can see what you are appending to without
being able to edit the file from the overlay.

Committing is explicit and always whole-document:

| Destination | What it does |
|---|---|
| Append to bound | `joinForAppend(existing, serializeNote(materialize(pending)))` — the addition lands on its own line, never welded onto the last one. |
| Replace bound | The pending document becomes the note. |
| New note | Names the note first, creates it, then moves each staged capture into its assets directory and rewrites the ref. |

**There is no live-append to a bound file.** That would write every half-typed word and every
backspace to disk permanently.

Pending blocks persist to `prefs.overlayPending` on every edit, so a killed service does not lose the
draft. `FileEditorScreen`'s `⋮` menu gained **SEND TO OVERLAY** (bind the drawer to this note and open
it) and **SEND TEXT TO OVERLAY** (append the note's prose to the pending document).

### Phase 5 — capture keeps the image

Four separate faults all presented as "the capture screen does not open", and all four are fixed:

1. **No `MediaProjection.Callback` registered before `createVirtualDisplay`.** On target 34+ this
   throws `IllegalStateException("Must register a callback before starting capture")`. Registered
   before the virtual display is created, and its `onStop` tears the projection down rather than
   leaving a timeout to expire.
2. **No `mediaProjection`-typed foreground service running when `getMediaProjection` was called.**
   Android 14 refuses to hand over a projectable `MediaProjection` unless one is. It cannot be raised
   before the user consents either, so the earliest legal moment is the consent result callback —
   which is where `OverlayService.onCaptureStarted()` is called now.
3. **The virtual display was sized from `resources.displayMetrics`.** That describes the activity's
   window, which the system insets to clear the status and navigation bars, so the mirror was a
   smaller region than the screen and everything in the capture was scaled. Now
   `windowManager.maximumWindowMetrics.bounds` (API 30+) with `Display.getRealSize` below it.
4. **A silent failure left the UI stuck.** A 4-second frame timeout now reports "the screen produced
   no image" instead of waiting forever.

The picture is kept: crop → `CaptureStore.downscale` → `CaptureStore.stage` → OCR → deliver both. OCR
failure still delivers the image, because the image is the thing that cannot be reconstructed.

`looksBlank` samples a 24×24 grid and warns on the crop screen when the frame is black — `FLAG_SECURE`
apps return an all-black frame, and "no text found" is a much worse explanation of that than "this app
blocks screenshots". The crop screen also gained fit-scaling (so a screenshot larger than the window
is fully visible and the coordinate mapping agrees with what is on screen), corner ticks, and a
pixel-dimension readout.

### Phase 6 — cleanup and docs

- Deleted: `ui/main/MainScreen.kt`, `ui/main/MainScreenViewModel.kt`, `data/DataRepository.kt`,
  `ui/OverlayDrawerContent.kt`, and both template tests (`MainScreenViewModelTest`,
  `MainScreenTest`) plus the now-empty `androidTest` tree.
- Added `app/proguard-rules.pro`, which `build.gradle.kts` referenced and did not exist — a latent
  `assembleRelease` break. **It is untested:** `isMinifyEnabled` is `false`, so R8 never runs and the
  rules are never applied. They only start mattering if minification is turned on.
- `res/values/themes.xml` — `Theme.WritersBlock` was `Theme.Material.Light.NoActionBar`, so the app
  flashed white before Compose drew its first frame. It is now the dark parent with an explicit black
  `windowBackground`. The transparent variant for `CaptureActivity` gained
  `windowShowWallpaper=false`, and its `windowLayoutInDisplayCutoutMode` lives in `values-v27/` — the
  attribute is API 27 and minSdk is 24, and naming an attribute a platform does not have is a lint
  error, not a no-op. (That split is what hid the Phase 9 bug: the two files were written as though
  they merged, and they do not.)
- `CaptureActivity` gained `excludeFromRecents` and `launchMode=singleTop`.
- `DashboardScreen` gained the OPEN DRAWER button and rewritten usage text.

### Phase 7 — the scan actually runs

Phase 5 fixed four reasons the capture screen never opened. It opened, and still captured nothing on
the screens people most want to scan. Three more faults:

1. **The frame listener was registered after the virtual display was created.** A mirrored screen that
   is *not changing* produces exactly one frame and then nothing. A listener attached a moment too
   late misses that one frame and then waits forever for a second that never comes — which is
   precisely the still, text-heavy screen worth scanning. The listener is now registered before
   `createVirtualDisplay`, every frame replaces the newest one held, and the capture is taken 350 ms
   after the *first* frame arrives rather than on a fixed timer from projection start. A slow start
   now delays the capture instead of being indistinguishable from a failure.
2. **A capture taken from a closed drawer showed nothing.** The drawer restored itself to closed, so
   the user saw a crop screen, a toast, and then no evidence that anything had happened. The service
   now tracks whether the capture produced anything and reopens the drawer when it did.
3. **There was no way in from inside the app.** `OverlayService.capture(context)` starts a capture from
   the home screen, and returns false when the service is off so the caller can send the user to the
   settings screen rather than doing nothing visible.

`CropGeometry` was extracted from `CropScreen` while fixing this. It is the arithmetic between the
point the user drags and the pixel that gets cropped — a fit-and-centre scale, plus the mapping both
ways. Extracted because it is the one part of the capture that fails *silently*: get it wrong and the
crop still succeeds, it just contains the wrong pixels, and the only way to notice would be to run it
on a device and squint. It is now a plain value class with 10 tests (`CropGeometryTest`), including
that the fitted image always fits (so no region is unreachable) and that the letterboxed margin clamps
to the image edge instead of past it.

### Phase 8 — interface pass

Consistency first: `theme/Dimens.kt` holds one spacing scale (`Gap`) and one set of radii (`Corner`),
replacing the `RoundedCornerShape(4.dp)` that appeared in every screen and read as unfinished.
`theme/Color.kt` gained `SlateRaised`, `BorderBright` and `Danger`, so three copies of a hardcoded
`0xFFEF5350` became one named value reserved for destruction. `labelSmall` was missing from
`Typography` — the only text in the app not in a chosen font, silently falling back to Material's.

Emoji icons (📁 📄 ⚙ ✏️ ✕ ⋮) are gone. They render in full colour on Android and sat badly against a
strictly monochrome, monospace interface. `ui/Icons.kt` draws fifteen monochrome glyphs with Compose
`Canvas` primitives on a unit square, so one stroke weight scales with the size and the set stays
optically consistent. No icon dependency: `material-icons-core` is not in the Gradle cache and would
have added a library for eight shapes.

Functional changes that came out of using the screens:

- **`HomeScreen` had two doors into one room.** OPEN FOLDER and OPEN FILE both navigated to
  `FileBrowser(null)`. Now SCAN SCREEN (the full-width primary action, disabled with an explanation
  when the service is off), BROWSE, and NEW NOTE.
- **`FileBrowserScreen` was a dead end.** Back always went home, from any depth. Back now goes up one
  level and only leaves the browser at the root, and the path is a row of tappable breadcrumbs. The
  delete affordance is visible rather than hidden behind a long-press, which is kept as a shortcut.
- **The floating handle was 16 dp wide** — under half the 48 dp minimum touch target, which made the
  drag the hardest gesture in the app to hit. 22 dp, and it gained a hairline edge: a plain white pill
  is invisible against a white app.
- **SCAN was one of three identical grey buttons** in the drawer footer. It now carries the icon and
  the brighter edge. OCR also showed no progress at all — a button that stops responding is
  indistinguishable from a tap that missed — so the crop screen shows an indeterminate bar while it
  works.
- The editor header names the note's folder, which is what tells two notes of the same name apart.

### Phase 9 — the capture stops being a picture of itself

Reported as "it is technically scanning but it is a blank screen it is scanning". It was: the scan ran,
succeeded, and kept a picture of our own black window.

1. **A qualified style replaces the unqualified one; it does not merge with it.** `values-v27/themes.xml`
   redefined `Theme.WritersBlock.Transparent` with only `windowLayoutInDisplayCutoutMode`, and the six
   transparency items lived in `values/themes.xml`. On every device on API 27 or newer — which is every
   device — the v27 definition won outright, so `windowIsTranslucent` and the transparent
   `windowBackground` did not exist, and the style inherited `windowBackground = @android:color/black`
   from `Theme.WritersBlock`. `CaptureActivity` was an opaque black full-screen window, and the mirror
   faithfully captured it. The transparency now lives in a `Theme.WritersBlock.Transparent.Base` style
   that both variants inherit from, so the v27 file adds its one attribute instead of replacing the lot.
   Checked in the compiled resource table rather than in the XML: the `(v27)` entry is one attribute
   long and its parent is `…Transparent.Base`, which carries all six.
2. **A blank frame is now looked past.** A frame grabbed while the mirror is coming up — the consent
   dialog on its way out, the window not yet composited — is black, and taking it keeps a picture of
   nothing. Nothing distinguishes that frame from a genuinely black screen, so up to three blank frames
   are set aside and the mirror is looked at again; then the blank one is accepted, and the crop screen
   shows its `FLAG_SECURE` warning. This also takes the load off the three fixed delays in the capture
   path (180 ms for the overlay to leave, 350 ms for the consent dialog to leave, 350 ms of settle):
   they are still there, but a wrong guess about any of them is no longer a black capture.
3. **Android 14's "share a single app" leaves the crop behind the app it shares.** Choosing an app in
   the consent dialog brings that app to the front to confirm it, so the user's next move was to
   navigate back to WritersBlock before they could crop. `CaptureActivity` now brings itself back to
   the front once the frame is taken — after it, not before, so this screen is still out of the way
   while the screen is being captured. It is sent to itself, which arrives as `onNewIntent`; the
   request therefore carries no `EXTRA_NEW_CAPTURE`, and `onNewIntent` will only ask for consent again
   when that extra is present. Without the marker this would be an endless consent dialog.
4. **IMAGE ONLY.** Keeping the picture without reading it is a button on the crop screen, not a
   degraded version of the other one. `CropAction` is an enum rather than a `readText: Boolean` because
   the outcome is being chosen by which of two buttons was pressed. That path never starts a
   recogniser: crop → downscale → stage → deliver, so there is nothing to wait for and nothing that can
   fail on its own. On that path a stage failure is reported instead of finishing quietly, because
   there is no reading to fall back on.
5. **A second scan while the first is in flight discarded the first.** `singleTop` reuses this activity
   for a second request, and the old projection, its reader and its newest frame were left standing.
   Since the leftover frame is the newest one available, the second capture could take the screen as it
   was *before* the request — including the consent dialog. A new capture now tears the old one down
   first, which is safe to do because the callback is unregistered before the projection is stopped, so
   the teardown cannot be mistaken for the user revoking the capture that is just starting.


### Phase 10 — castle redesign, scan fixes, emulator verification

The first phase checked on a running system: the `Medium_Phone_API_36.1` emulator, driven over adb with
screenshots. Phases 3–9 behaviour that the run confirmed is noted in Risks below.

**What was wrong when the old build was run:**

1. **The scan's consent dialog defaulted to "Share one app".** Android 14+ shows that first; picking it
   sends the user through an app picker, brings that app to the front and leaves the crop screen behind
   it. This is what "the scan mostly does not work" looked like. Consent is now requested with
   `MediaProjectionConfig.createConfigForDefaultDisplay()` on API 34+, so the dialog offers the whole
   screen and a single **Share screen** button. Phase 9's `bringToFront` and `EXTRA_NEW_CAPTURE`, which
   only existed to recover from the single-app path, are deleted.
2. **Settings showed a "CAPTURE — Granted" chip** that was really the notification permission. Capture
   consent cannot be granted ahead of time. Renamed to what it is; the duplicate "recent captures" list
   on that screen is gone (it was the home screen's recent notes again).
3. **An orphaned empty line above every first capture.** `appendImage` now drops a trailing empty text
   block instead of leaving it above the picture (two new tests in `NoteDocumentTest`).
4. **Back did nothing in the drawer.** An overlay window has no back dispatcher. The drawer now catches
   Back on a focusable root: it closes a sheet if one is open, otherwise rolls the drawer up.
5. **Home read `OverlayService.isRunning` itself**, racing the service start. It is passed down from
   `MainActivity`, which already tracks it.
6. **Overlay windows used `TYPE_APPLICATION_OVERLAY` unconditionally**, which does not exist below API 26
   (minSdk is 24). `TYPE_PHONE` is used there.

**The redesign.** A cartoon-medieval look in purple and orange, built as one kit in `ui/Medieval.kt`:

- Every screen sits on `CastleBackdrop`: a night-sky gradient, fixed-seed stars, a crescent moon rising
  between two towers, and a crenellated castle wall with a lit window and an orange pennant. All Canvas
  drawing; no image assets.
- Screen titles are swallowtail ribbon `Banner`s; sections are marked with a small gold pennant.
- Surfaces are `StoneCard`s (purple, thick dark outline, hard drop shadow). Buttons are `RoyalButton`s:
  chunky blocks on a darker base that sink when pressed. Orange is the primary action, stone the rest,
  red only for destruction.
- **The drawer is a parchment scroll.** Wooden rollers top and bottom, ink on parchment. Opening it
  unrolls the parchment down from the top roller (`ScrollPanel(unroll = …)`, a clip and an offset read
  in the draw/layout phases only); closing rolls it back up. Dragging right still flings it away. Its
  sheets are smaller scrolls that rise from the bottom.
- The floating handle is the drawer rolled up: a small parchment roll tied with an orange ribbon.
- Note editing, dialogs and the drawer are all written on parchment; the editor text is the system serif.
- Fonts: MedievalSharp (OFL, `res/font/medieval_sharp.ttf`, licence in `docs/fonts-OFL.txt`) for names
  and labels, the system serif for prose.
- New launcher icon: a scroll with an orange wax seal on a night sky. (The legacy `mipmap-*/*.webp`
  icons used on API 24–25 are still the template's.)

**Verified on the emulator:** home, settings, library, editor, and dialogs render as designed; waking the
drawer shows the handle; tapping it unrolls the scroll; the save sheet rises above the keyboard (the IME
risk below); saving as a new note writes `Castle Notes.md` plus its `.assets/` picture and binds the
drawer to it; a scan started from the drawer shows the whole-screen consent, the crop screen, and lands
the picture plus OCR text back in the reopened drawer; Back closes the keyboard, then the drawer.

## Risks

1. **IME in an overlay window** — works on the API 36 emulator: the drawer and its sheets move above the
   keyboard. OEM keyboards and older Android versions are still unchecked.
2. **`FLAG_SECURE` apps return a black frame** — banking, Netflix, some chat apps. Not fixable. Now
   reported on the crop screen instead of arriving as a silent black rectangle. Note that the
   blank-frame retry in Phase 9 costs such a capture about a second before it gives up looking.
3. **MediaProjection re-consent.** Consent is requested on every capture, so Android shows its dialog
   each time (now one tap: **Share screen**). Tolerable; a per-gesture tax once capture is used more.
4. **Per-block text fields** mean the cursor cannot cross an image, and backspace at a block's start
   does not merge into the previous block.
5. **The capture race is fixed by reasoning, not by observation.** Registering the listener first is
   the documented ordering, and it is the only explanation that fits "a still screen never captures",
   but nothing has confirmed it on hardware. If scanning still fails, the frame timeout now fires at
   5 s and says so instead of hanging.
6. **The Phase 9 transparency fix holds on the emulator:** the crop screen shows the app underneath,
   not a black frame. `FLAG_SECURE` apps still return black and are still reported as such.
7. **Below API 34 the consent dialog cannot be locked to the whole screen**, but those versions have no
   "share one app" option either, so there is nothing to lock.
8. **Every timing in the capture path is a guess.** 180 ms, 350 ms, 350 ms, and now 3 × 350 ms of
   retries: none of it has been measured against a real consent dialog on real hardware.
9. **Only the emulator has run this.** Everything marked verified was observed on the API 36 emulator,
   not on physical hardware, OEM skins, or older Android versions.


## Verification

```bash
./gradlew :app:testDebugUnitTest    # 96 tests: 32 format, 33 pending store, 21 document, 10 crop geometry
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:lintDebug            # 0 errors; warnings are dependency/KTX notices
```

All four pass from a clean tree. The lint warnings are dependency-update notices plus two false
positives: `ClickableViewAccessibility` on the handle (lint cannot see the `performClick()` inside the
`OnTouchListener` lambda) and `StaticFieldLeak` on the deliberately-held service instance, which is
nulled in `onDestroy`.

Manual, on device — the list below is the entire remaining gate:

1. Grant overlay + notification permissions; toggle the master switch.
2. Tap the handle → drawer slides in → type with the keyboard (**the Phase 3 gate**).
3. Drag the panel right and flick it right; tap the scrim. All three should close it.
4. **Still document open on screen, nothing moving** → SCAN SCREEN from the home screen → the crop
   screen must appear within a second or so. This is the Phase 7 gate: it is the case that used to
   time out with "the screen produced no image".
5. **The Phase 9 gate: the crop must show the screen, not black.** Scan from the drawer's handle while
   looking at a text-heavy app, and confirm the crop screen draws that app — not a black rectangle, and
   not the WritersBlock UI underneath it. If it is black and the warning says `FLAG_SECURE`, the app
   genuinely blocks capture; if it is black with no warning, the transparency is still not reaching the
   window.
6. The consent dialog offers only the whole screen and a single **Share screen** button (API 34+).
7. Crop → **KEEP + READ**: confirm the image block and the OCR text both land in the drawer, and that
   the selected region is the region that arrives — not scaled or shifted. Try a drag up-and-left as
   well as one down-and-right; try FULL SCREEN.
8. Crop → **IMAGE ONLY**: the image block must land with no text block after it, and it must arrive
   appreciably faster than the read path does on the same region.
9. **Capture with the drawer closed** — the drawer must be open afterwards, holding the capture. Then
   the same from the drawer's own SCAN button, and confirm the drawer is open again after.
10. SAVE → each of append, replace and new-note-with-a-folder; verify in the app.
11. Bind the drawer to an existing note → confirm its text is readable above the composer → append →
    verify in the app.
12. Kill the service mid-sentence → reopen → draft survives.
13. In `FileBrowserScreen`, walk two folders deep and confirm back goes up one level each time, the
    breadcrumbs navigate, the trailing ✕ deletes, and no `.assets` directory or `![` snippet appears.
14. Open a `FLAG_SECURE` app → SCAN → confirm the warning is legible.
15. Flick the handle open and closed a few times. It is 22 dp wide now and should be catchable; the
    old 16 dp pill was not.

