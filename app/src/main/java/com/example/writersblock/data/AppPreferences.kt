package com.example.writersblock.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Everything the overlay needs to survive a process death.
 *
 * The drawer is a service, so it can be killed at any moment while the user is mid-sentence. Its state
 * therefore lives here rather than in memory: the draft of the page it shows (autosaved on every
 * edit), which note (if any) that page is, the note's content when last saved, and where the user last
 * parked the floating handle.
 */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The drawer's unsaved document, encoded by [encodePending].
     *
     * Read falls back to [legacyDraft] so a draft written by an earlier build — one plain string, no
     * images — is not silently discarded on upgrade.
     */
    var overlayPending: String
        get() {
            val stored = prefs.getString(KEY_OVERLAY_PENDING, "") ?: ""
            if (stored.isNotEmpty()) return stored
            val legacy = legacyDraft
            return if (legacy.isEmpty()) "" else encodePending(listOf(NoteBlock.Text(text = legacy)))
        }
        set(value) = prefs.edit().putString(KEY_OVERLAY_PENDING, value).apply()

    /**
     * The note the drawer is showing, as an absolute path. Empty means the loose page.
     *
     * This is the mode flag itself rather than a second setting that has to be kept in step with this
     * one — "is it bound" and "which note" are the same question.
     */
    var overlayActiveFilePath: String
        get() = prefs.getString(KEY_OVERLAY_ACTIVE_FILE_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OVERLAY_ACTIVE_FILE_PATH, value).apply()

    /**
     * The bound note's content as it was when the drawer last loaded or saved it: what the draft is
     * compared against to tell whether there are unsaved changes. Null when never stored, which is
     * how a draft left by the older, append-only drawer is recognised.
     */
    var overlayBaseline: String?
        get() = prefs.getString(KEY_OVERLAY_BASELINE, null)
        set(value) = prefs.edit().putString(KEY_OVERLAY_BASELINE, value).apply()

    /** Vertical position of the floating handle, in window coordinates. 0 means centred. */
    var overlayHandleY: Int
        get() = prefs.getInt(KEY_OVERLAY_HANDLE_Y, 0)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_HANDLE_Y, value).apply()

    /** The pre-pending-store draft key. Read once for migration, never written. */
    private val legacyDraft: String
        get() = prefs.getString(KEY_OVERLAY_DRAFT, "") ?: ""

    /** Forgets the draft once it has been committed to a file. */
    fun clearPending() = prefs.edit().putString(KEY_OVERLAY_PENDING, "").apply()

    companion object {
        private const val PREFS_NAME = "writersblock_prefs"
        private const val KEY_OVERLAY_DRAFT = "overlay_draft"
        private const val KEY_OVERLAY_PENDING = "overlay_pending"
        private const val KEY_OVERLAY_ACTIVE_FILE_PATH = "overlay_active_file_path"
        private const val KEY_OVERLAY_HANDLE_Y = "overlay_handle_y"
        private const val KEY_OVERLAY_BASELINE = "overlay_baseline"
    }
}
