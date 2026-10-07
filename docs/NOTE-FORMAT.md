# Note format

A note is one scrollable document mixing prose and captured images. This file is the spec for how
that document is stored on disk. The implementation is `data/NoteFormat.kt`; its behaviour is pinned
by `app/src/test/java/com/example/writersblock/data/NoteFormatTest.kt`.

## Layout

```
<notesRoot>/<Folder>/<Name>.md
<notesRoot>/<Folder>/<Name>.assets/<id>.png
```

`notesRoot` is `context.filesDir/notes`. Images live in a directory *sibling* to the note, not inside
a container file, so notes stay greppable, diffable, portable and readable by anything that reads
text.

Existing `.txt` captures remain valid and still appear in the browser and in recents, but new notes
are written as `.md`.

## The image reference

An image is referenced by a line of exactly this form:

```
![capture 14:30](Meeting notes.assets/2026-09-25_14-30-12.png)
```

The alt text is a human-readable caption; the target is a path relative to the directory holding the
note.

**The reference is storage, never display.** The editor parses it into an image block and renders the
picture in position. The raw `![…](…)` syntax is never shown to the user.

## The guard, and why there is no escaping scheme

A line is an image reference **only if its target resolves into that note's own sibling assets
directory**. Everything else is literal text. A target must:

- not start with `/`
- not contain `..`
- contain a `/`, with a non-empty, whitespace-free file part after the first slash
- have a first path segment exactly equal to `assetsDirName(noteFileName)`

This is what makes an escaping scheme unnecessary. A line of prose that merely looks like a reference
— `see ![the diagram](fig 3) for details` — cannot be misinterpreted, because it does not point at a
real path in this note's own assets. Only the note's own files are ever in scope.

Note that whitespace *is* permitted before the first slash (it has to be: a note called
`Meeting notes.md` owns `Meeting notes.assets`), but not in the file name after it.

## `assetsDirName`

`Note.md` → `Note.assets`. A file whose extension is not a note extension keeps it:
`Capture_2026-09-25.txt` → `Capture_2026-09-25.txt.assets`.

Only `.md` is stripped. Stripping *any* extension would map both `Notes.md` and a legacy `Notes.txt`
onto the same `Notes.assets`, and the two would silently share captures.

## Blocks and the round-trip guarantee

```kotlin
sealed interface NoteBlock {
    val id: String                                    // stable, for Compose keys; not persisted
    data class Text(val id: String, val text: String) : NoteBlock
    data class Image(val id: String, val alt: String, val ref: String,
                     val trailingNewline: Boolean = true) : NoteBlock
}
```

`parseNote(content, noteFileName)` splits on `\n` keeping delimiters, accumulates consecutive
non-reference lines into a `Text` block, and flushes on each reference line.
`serializeNote(blocks)` writes `Text` verbatim and `Image` as `![alt](ref)\n`.

**`serializeNote(parseNote(x)) == x`, byte for byte.** This is the property the whole editor rests on:
it is what lets the editor use a plain `serialized != originalContent` dirty check. Without it, merely
opening and re-saving a note would rewrite the file and fire the 30-second auto-save spuriously.

### `trailingNewline`

An image reference occupies a whole line, so it *owns* the newline that terminates that line — and a
reference on the final line of a file with no trailing newline owns none. Hence the flag. Without it,
saving a note that ends in an image would silently append a byte and mark the file dirty forever.

`id` is regenerated on every parse. It exists only to key Compose state, and must never be written to
disk.

## Snippets

`FileRepository` previews rows with the first two non-blank prose lines. Reference lines are stripped
first, so a preview never reads `![capture 14:30](…)`. An image-only note has an empty snippet.

The browser also skips `*.assets` directories entirely (both when listing a folder and when walking
recursively), so captures do not show up as items. Deleting a note deletes its sibling assets
directory, so images are not orphaned.

## Known limitations

- **Per-block text fields.** The cursor cannot cross an image, and backspace at the start of a block
  does not merge it into the previous one. Accepted for now.
- **CRLF files.** Reference lines ending `\r\n` do not match (the `\r` sits after the `)`), so they
  are treated as literal text. This preserves bytes — the round-trip still holds — but a CRLF note
  would display its raw syntax. Notes written by the app use `\n`.
