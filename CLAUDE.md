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

**Check runtime behaviour on the emulator, not just the build.** A green build plus passing tests only
means "compiles and the pure logic holds". The SDK is at `C:/Users/kashi/AppData/Local/Android/Sdk` and
there is one AVD, `Medium_Phone_API_36.1`; the user is happy for it to be used. Drive it with adb
(`input tap/swipe/text/keyevent`, `exec-out screencap -p`) and look at the screenshots:

```bash
ANDROID_SDK=C:/Users/kashi/AppData/Local/Android/Sdk
"$ANDROID_SDK/emulator/emulator.exe" -avd Medium_Phone_API_36.1 -no-snapshot-save   # run in background
"$ANDROID_SDK/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_SDK/platform-tools/adb.exe" shell appops set com.example.writersblock SYSTEM_ALERT_WINDOW allow
"$ANDROID_SDK/platform-tools/adb.exe" shell pm grant com.example.writersblock android.permission.POST_NOTIFICATIONS
```

Reinstalling kills the overlay service; wake it again from Settings. Emulator-verified is still not
physical-device-verified (see the Risks in `PLAN.md`).

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
- `ui/Medieval.kt`: the **design kit**, every themed shape drawn with Canvas (no image assets):
  `CastleBackdrop` (night sky, moon, castle wall: every screen sits on one), `Banner`, `PennantLabel`,
  `StoneCard`, `RoyalButton` (+ `ButtonKind`), `ScrollPanel` / `ScrollCard` / `Roller` (the parchment
  scroll), `ScrollHandle` (the floating handle), `ScrollDialog` / `NameDialog` / `ConfirmDialog`,
  `InkField`, `NoteRow`, `ScreenHeader`. Build new UI from these rather than from raw Material widgets.
- `ui/NoteDocument.kt`: the **one shared editor**, used by both `FileEditorScreen` (in-app) and
  `OverlayDrawer` (overlay). Also holds the `SnapshotStateList<NoteBlock>` edit helpers.
- `service/OverlayService`: a foreground service that is also its own `LifecycleOwner`,
  `SavedStateRegistryOwner` and `ViewModelStoreOwner`, so it can host a `ComposeView` in `WindowManager`.
  It owns the floating handle and the full-screen drawer window (both Compose: `ScrollHandle`, `ui/OverlayDrawer.kt`).
  It holds the pending blocks and mirrors them to prefs on every edit. Its companion exposes a static
  `instance` and is the only bridge to the rest of the app (`openDrawer`, `capture`, `pushTextToDrawer`,
  `deliverCapture`, …).
- `service/CaptureActivity`: a transparent activity. It handles MediaProjection consent, grabs a frame,
  shows the crop screen, runs OCR, then calls `OverlayService.deliverCapture`. `CropGeometry` is the
  screen↔bitmap coordinate math, extracted so it can be tested.
- `theme/`: the castle-at-night palette in `Color.kt` (sky/stone purples, `Ember` orange banners,
  `Parchment`/`Ink` for the scroll, `Blood` reserved for destructive actions), `Dimens.kt` (`Gap`,
  `Corner`, `Pen` outline widths), and `Type.kt`: `Medieval` (bundled MedievalSharp, OFL licence in
  `docs/fonts-OFL.txt`) for titles, labels and buttons, `Book` (system serif) for prose. Icons are
  hand-drawn Canvas glyphs in `ui/Icons.kt`. There is no icon library and no emoji, on purpose.

Dark screens use stone + lavender text; anything written on (editor, drawer, dialogs) is ink on parchment.

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
- **Read the drawer's `unroll` / `dragX` animations only in the layout and draw phases**
  (`Modifier.offset { }`, `drawBehind`, `drawWithContent`; `ScrollPanel` takes `unroll` as a lambda for
  this reason). Reading them during composition recomposes the editor on every animation frame.
- **The overlay has no back dispatcher.** Back is caught with `onPreviewKeyEvent` on the drawer's
  focusable root; keep that root focusable or Back stops closing the drawer.
- **Overlay commits are explicit and whole-document:** append, replace, or new note. Nothing is
  live-appended to a bound file.
- **Capture ordering matters.** Register the `MediaProjection.Callback` and the ImageReader listener
  *before* `createVirtualDisplay`. Raise the service's `mediaProjection` foreground type only after
  consent (`onCaptureStarted`). Size the display from `maximumWindowMetrics`, not `displayMetrics`.
  On API 34+ consent is requested with `MediaProjectionConfig.createConfigForDefaultDisplay()`; without it
  Android defaults to "Share one app", which sends the user through an app picker.
- **Qualified resource styles replace the unqualified one; they do not merge with it.** Transparency
  items live in `Theme.WritersBlock.Transparent.Base`, which both `values/` and `values-v27/` inherit.
