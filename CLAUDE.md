# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

WritersBlock: a single-module Android app (Kotlin, Jetpack Compose, Navigation 3, ML Kit OCR). It is a
system-wide floating capture drawer over a plain-text notes tree. You can type into the drawer from any
app, or scan the screen (MediaProjection, then crop, then OCR) and drop the image and its text into a note.

`docs/PLAN.md` holds the design decisions, phase status, known risks and the manual device test list.
`docs/NOTE-FORMAT.md` is the on-disk note format spec. Read both before changing note storage, the
overlay, or the capture path, and update `PLAN.md` when a phase or risk changes.

## Commands

```bash
./gradlew :app:testDebugUnitTest                      # all JVM unit tests
./gradlew :app:testDebugUnitTest --tests "com.example.writersblock.data.NoteFormatTest"   # one class
./gradlew :app:testDebugUnitTest --tests "*NoteFormatTest.someTestName"                  # one test
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease                        # minify is off, so proguard-rules.pro is never applied
./gradlew :app:lintDebug
```

JDK 17 toolchain, compileSdk/targetSdk 36, minSdk 24. Dependency versions live in `gradle/libs.versions.toml`.
There are no instrumented tests (`androidTest` was deleted) and no Robolectric. Only plain-JVM logic is
tested: `NoteFormat`, `PendingStore`, the `NoteDocument` list-editing helpers, and `CropGeometry`.

**Nothing has been verified on a device.** A green build plus passing tests means "compiles and the pure
logic holds". It does not mean the overlay, IME, or capture actually work. Report results that way, and
add to the manual checklist in `PLAN.md` rather than claiming runtime behaviour.

## Architecture

Package `com.example.writersblock`:

- `MainActivity` + `Navigation.kt` / `NavigationKeys.kt`: Navigation 3 back stack with `@Serializable`
  `NavKey`s (Home, FileBrowser, FileEditor, Dashboard). MainActivity owns the overlay/notification
  permission state and starts or stops the service.
- `data/`: storage. Keep it free of Compose and, apart from `CaptureStore`, free of `android.graphics`,
  so it stays unit-testable.
  - `NoteFormat.kt`: `NoteBlock` (Text | Image), `parseNote` / `serializeNote`, assets-dir naming, and
    the image-ref guard.
  - `FileRepository`: the notes tree under `filesDir/notes`. Lists, snippets and deletes. It skips
    `*.assets` dirs, and deleting a note also deletes its assets dir.
  - `CaptureStore`: bitmap downscale and staging of captured PNGs before they belong to a note.
  - `PendingStore.kt`: a length-prefixed codec (`WB1`) for the drawer's unsaved document. Staged image refs
    in it are absolute paths until commit, when `materializeBlocks` moves them into the note's assets dir.
  - `AppPreferences`: SharedPreferences. `overlayActiveFilePath` is the mode flag itself: empty means
    scratch mode, a path means the drawer is bound to that note.
- `ui/NoteDocument.kt`: the **one shared editor**, used by both `FileEditorScreen` (in-app) and
  `OverlayDrawer` (overlay). Also holds the `SnapshotStateList<NoteBlock>` edit helpers.
- `service/OverlayService`: a foreground service that is also its own `LifecycleOwner`,
  `SavedStateRegistryOwner` and `ViewModelStoreOwner`, so it can host a `ComposeView` in `WindowManager`.
  It owns the floating handle (a plain View) and the full-screen drawer window (Compose, `ui/OverlayDrawer.kt`).
  It holds the pending blocks and mirrors them to prefs on every edit. Its companion exposes a static
  `instance` and is the only bridge to the rest of the app (`openDrawer`, `capture`, `pushTextToDrawer`,
  `deliverCapture`, …).
- `service/CaptureActivity`: a transparent activity. It handles MediaProjection consent, grabs a frame,
  shows the crop screen, runs OCR, then calls `OverlayService.deliverCapture`. `CropGeometry` is the
  screen↔bitmap coordinate math, extracted so it can be tested.
- `theme/`: `Dimens.kt` (`Gap`, `Corner`), named colours, and typography. Icons are hand-drawn Canvas
  glyphs in `ui/Icons.kt`. There is no icon library and no emoji, on purpose.

## Invariants that are easy to break

- **`serializeNote(parseNote(x)) == x` byte for byte.** The editor's dirty check and its 30s auto-save
  depend on it. The `Image.trailingNewline` flag exists for this. Block `id`s are regenerated on every
  parse and are never written to disk.
- **An image ref only counts if it points into that note's own `<Name>.assets/` dir**, as specified in
  `NOTE-FORMAT.md`. That guard is why there is no escaping scheme. Only `.md` is stripped when deriving
  the assets dir name (`Notes.txt` → `Notes.txt.assets`).
- **Edit blocks in place, never rebuild the list.** `replaceText` reassigns only one element. Rebuilding
  the list changes block identities, which recreates the `BasicTextField` and resets the caret. An empty
  document must be seeded with one empty block (`seedIfEmpty`), or the first keystroke closes the keyboard.
- **No `Dialog` / `ModalBottomSheet` inside the overlay.** A service has no window token
  (`BadTokenException`). Drawer sheets are in-window `Box` siblings.
- **Read the drawer's slide offset only in the layout and draw phases** (`Modifier.offset { }`,
  `drawBehind`). Reading it during composition recomposes the editor on every animation frame.
- **Overlay commits are explicit and whole-document:** append, replace, or new note. Nothing is
  live-appended to a bound file.
- **Capture ordering matters.** Register the `MediaProjection.Callback` and the ImageReader listener
  *before* `createVirtualDisplay`. Raise the service's `mediaProjection` foreground type only after
  consent (`onCaptureStarted`). Size the display from `maximumWindowMetrics`, not `displayMetrics`.
  `CaptureActivity` re-launches itself to come back to the front, so it only asks for consent again
  when `EXTRA_NEW_CAPTURE` is present.
- **Qualified resource styles replace the unqualified one; they do not merge with it.** Transparency
  items live in `Theme.WritersBlock.Transparent.Base`, which both `values/` and `values-v27/` inherit.
