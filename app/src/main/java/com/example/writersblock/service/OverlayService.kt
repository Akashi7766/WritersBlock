package com.example.writersblock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.writersblock.data.AppPreferences
import com.example.writersblock.data.CaptureStore
import com.example.writersblock.data.FileRepository
import com.example.writersblock.data.NoteBlock
import com.example.writersblock.data.captionForCapture
import com.example.writersblock.data.decodePending
import com.example.writersblock.data.encodePending
import com.example.writersblock.data.joinForAppend
import com.example.writersblock.data.materializeBlocks
import com.example.writersblock.data.parseNote
import com.example.writersblock.data.serializeNote
import com.example.writersblock.theme.WritersBlockTheme
import com.example.writersblock.ui.OverlayDrawer
import com.example.writersblock.ui.ScrollHandle
import com.example.writersblock.ui.SaveRequest
import com.example.writersblock.ui.appendImage
import com.example.writersblock.ui.appendText
import com.example.writersblock.ui.removeBlock
import com.example.writersblock.ui.replaceText
import com.example.writersblock.ui.seedIfEmpty
import java.io.File
import kotlin.math.abs

/**
 * Hosts the floating handle and the capture drawer as system overlay windows.
 *
 * The drawer is a single full-screen Compose window. That is what makes it swipable — the panel's
 * position is an animatable the drag gesture writes to, which a `WindowManager` translation could not
 * be dragged through smoothly — and it is why the editor is shared with the in-app one instead of
 * being reimplemented against `EditText`.
 *
 * Compose expects three owners that an activity would normally supply and a service is none of them: a
 * `ComposeView` fails on attach without at least a `LifecycleOwner` and a `ViewModelStoreOwner`. It is
 * made all three here, and the same instances are published to the view tree below so the drawer's
 * composition is tied to this service's lifetime rather than to nothing.
 *
 * The pending document is held here rather than in the composition, because the drawer is destroyed
 * every time the service is killed and a half-written note has to survive that. It is mirrored into
 * [AppPreferences] on every edit.
 */
class OverlayService : Service(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    companion object {
        private const val CHANNEL_ID = "writersblock_overlay_channel"
        private const val NOTIFICATION_ID = 1001

        /** Long enough for the drawer's slide (220ms) to settle before the caret asks for the keyboard. */
        private const val FOCUS_DELAY_MS = 260L

        const val ACTION_TOGGLE_DRAWER = "com.example.writersblock.TOGGLE_DRAWER"
        const val ACTION_OPEN_DRAWER = "com.example.writersblock.OPEN_DRAWER"
        const val ACTION_BIND = "com.example.writersblock.BIND"
        const val EXTRA_FILE_PATH = "com.example.writersblock.FILE_PATH"

        /**
         * The running instance, for the two things that cannot be done through an Intent: raising the
         * media-projection foreground type from [CaptureActivity], and handing a capture back.
         *
         * Holding this is the standard cost of a service that has a UI; it is cleared in [onDestroy]
         * so a stopped service is never reached through it.
         */
        @Volatile
        private var instance: OverlayService? = null

        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        /** Opens the drawer, optionally binding it to [filePath] first. No-op if not running. */
        fun openDrawer(context: Context, filePath: String? = null) {
            val service = instance ?: return
            if (filePath != null) service.bind(filePath)
            service.post { service.setDrawerOpen(true) }
        }

        /** True when the drawer is already bound to [filePath]. */
        fun isBoundTo(filePath: String): Boolean = instance?.boundPath?.value == filePath

        /**
         * Starts a screen capture from inside the app rather than from the floating handle.
         *
         * Returns false when the service is off, because the capture runs on the service's own window
         * and needs it to raise the `mediaProjection` foreground type — without a live instance there
         * is nothing to raise it on and `getMediaProjection` would be refused. Callers should send the
         * user to the settings screen rather than silently doing nothing.
         */
        fun capture(context: Context): Boolean {
            val service = instance ?: return false
            service.post { service.startCapture() }
            return true
        }

        /**
         * Appends [text] to whatever the drawer is already holding and opens it.
         *
         * Used by the in-app editor's "send text to overlay" action, for prose that is on its way
         * somewhere that is not a note yet — it joins the scratch document and can be captured around
         * before being saved once.
         */
        fun pushTextToDrawer(context: Context, text: String) {
            val service = instance ?: return
            service.post {
                service.pendingBlocks.appendText(text)
                service.pendingBlocks.seedIfEmpty()
                service.persistPending()
            }
        }

        /**
         * Starts a capture. [CaptureActivity] calls these around its projection so the foreground
         * service advertises the `mediaProjection` type for exactly as long as it holds one — Android
         * 14 refuses `getMediaProjection` without it, and refuses to *start* it before consent.
         */
        fun onCaptureStarted() {
            instance?.raiseCaptureForegroundType()
        }

        fun onCaptureEnded() {
            instance?.lowerCaptureForegroundType()
        }

        /**
         * Delivers a finished capture to the drawer: the OCR text and, when the crop was kept, the
         * staged file. Either may be empty; a capture of a picture with no words in it is still worth
         * keeping.
         */
        fun deliverCapture(text: String, stagedPath: String?) {
            instance?.appendCapture(text, stagedPath)
        }

        /** The drawer is gone, or the capture was abandoned: put the overlay back how it was. */
        fun onCaptureClosed() {
            instance?.restoreAfterCapture()
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: AppPreferences

    private var handleView: View? = null
    private var drawerWindow: ComposeView? = null
    private var viewsReady = false

    /** The drawer's unsaved work. Mirrored into prefs on every change. */
    private val pendingBlocks = mutableStateListOf<NoteBlock>()

    /** The note the drawer is bound to; empty means scratch. */
    private val boundPath = mutableStateOf("")

    private var boundBlocks by mutableStateOf<List<NoteBlock>>(emptyList())

    private val drawerOpen = mutableStateOf(false)
    private val focusRequestId = mutableIntStateOf(0)

    /** Bumps [focusRequestId] once the drawer's slide has settled and the window is focusable. */
    private val focusRunnable = Runnable { focusRequestId.intValue += 1 }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Whether the drawer was showing when a capture began, so it can be put back afterwards. */
    private var wasOpenBeforeCapture = false

    /** Whether the capture that just ended actually produced something worth showing. */
    private var capturedSomething = false

    /** Whether the mediaProjection foreground type is currently raised. */
    private var onCaptureType = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = AppPreferences(this)
        createNotificationChannel()

        // Compose reads its owners off the view tree when it attaches, and a composition whose
        // lifecycle never leaves INITIALIZED renders once and then never updates. Both have to be
        // done here, before the drawer window is built.
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        pendingBlocks.addAll(decodePending(prefs.overlayPending))
        pendingBlocks.seedIfEmpty()

        boundPath.value = prefs.overlayActiveFilePath
        reloadBoundBlocks()

        // A capture that was staged but never committed has nowhere to go once its note is gone.
        CaptureStore.pruneStaging(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true

        // Called before anything else: an action intent can be the first thing a freshly started
        // service sees, and both the notification and the drawer's own window must exist by the time
        // the action is handled.
        //
        // Skipped mid-capture: a toggle or a new intent arriving while the projection is live must not
        // drop the foreground type back down, or the projection is torn out from under us.
        if (!onCaptureType) {
            startForegroundCompat(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
        if (!viewsReady) setupOverlayViews()

        when (intent?.action) {
            ACTION_TOGGLE_DRAWER -> setDrawerOpen(!drawerOpen.value)
            ACTION_OPEN_DRAWER -> {
                intent.getStringExtra(EXTRA_FILE_PATH)?.let { linkPath ->
                    if (linkPath.isNotEmpty()) bind(linkPath)
                }
                setDrawerOpen(true)
            }
            ACTION_BIND -> {
                intent.getStringExtra(EXTRA_FILE_PATH)?.let { linkPath ->
                    if (linkPath.isNotEmpty()) bind(linkPath)
                }
                setDrawerOpen(true)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacks(focusRunnable)
        // Removing the views disposes the composition while the lifecycle is still alive; only then is
        // it safe to tear the owners down, or the ViewModelStore is cleared out from under it.
        removeAllViews()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun post(block: () -> Unit) = mainHandler.post(block)

    // ── Windows ───────────────────────────────────────────────────────────────────────────────

    private fun setupOverlayViews() {
        removeAllViews()
        buildDrawerWindow()
        buildFloatingHandle()
        viewsReady = true
    }

    /**
     * The rolled-up scroll on the right edge. Tap it to unroll the drawer, drag it up or down to move
     * it, or flick it leftwards — the same gesture that pulls the drawer out.
     */
    private fun buildFloatingHandle() {
        val dp = resources.displayMetrics.density
        // Wide enough to catch with a thumb; a thinner strip made the drag the hardest gesture to hit.
        val width = (30 * dp).toInt()
        val height = (104 * dp).toInt()

        val pill = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setContent { ScrollHandle(Modifier.fillMaxSize()) }
            contentDescription = "Open the WritersBlock drawer"
        }

        val params = overlayParams(width, height).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            y = prefs.overlayHandleY
        }

        var startY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        var dragging = false
        var flicking = false
        var touchedAt = 0L

        // Clickable so the tap below can be reported as a click. A bare touch listener is invisible to
        // accessibility services, which then announce a control that cannot be activated.
        pill.isClickable = true
        pill.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = params.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    dragging = false
                    flicking = false
                    touchedAt = System.currentTimeMillis()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startTouchX
                    val dy = event.rawY - startTouchY
                    if (!dragging && !flicking) {
                        when {
                            abs(dx) > abs(dy) && abs(dx) > 10 * dp -> flicking = true
                            abs(dy) > 10 * dp -> dragging = true
                        }
                    }
                    if (dragging) {
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    when {
                        flicking && event.rawX < startTouchX -> {
                            prefs.overlayHandleY = params.y
                            setDrawerOpen(true)
                        }
                        dragging -> prefs.overlayHandleY = params.y
                        System.currentTimeMillis() - touchedAt < 300 -> {
                            v.performClick()
                            setDrawerOpen(!drawerOpen.value)
                        }
                    }
                    true
                }

                else -> false
            }
        }

        wm.addView(pill, params)
        handleView = pill
    }

    /**
     * The drawer: one full-screen window holding the whole interface.
     *
     * Full screen so the scrim can cover the app behind it, and untouchable while closed so it never
     * swallows a tap meant for that app. The panel parks itself off the right edge inside Compose.
     */
    private fun buildDrawerWindow() {
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)

            setContent {
                WritersBlockTheme {
                    OverlayDrawer(
                        isOpen = drawerOpen.value,
                        onRequestClose = { setDrawerOpen(false) },
                        focusRequestId = focusRequestId.intValue,
                        boundNotePath = boundPath.value,
                        boundBlocks = boundBlocks,
                        onBind = ::bind,
                        pendingBlocks = pendingBlocks,
                        onPendingTextChange = { id, text ->
                            pendingBlocks.replaceText(id, text)
                            persistPending()
                        },
                        onPendingImageRemove = { id ->
                            pendingBlocks.removeBlock(id)
                            persistPending()
                        },
                        onCopy = ::copyPending,
                        onPaste = ::pasteIntoPending,
                        onCapture = ::startCapture,
                        onSave = ::handleSave,
                    )
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            closedDrawerFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            // The window is invisible until the drawer is open, but its visibility is not the same as
            // its interactivity — the flags above are what keep it out of the way. Alpha is what keeps
            // the parked panel off the screen entirely, since a view translated out of the window is
            // still drawn when the window is not laid out to the screen edge.
            alpha = 0f
            // An overlay is not an activity, so windowSoftInputMode from the manifest does not reach
            // it. Asking the window manager to resize is one of the two levers for keeping the
            // composer above the keyboard; the other is Compose's imePadding, applied in the drawer.
            // If the resize is honoured the inset reports zero and the two do not double up.
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        wm.addView(view, params)
        drawerWindow = view
    }

    private fun closedDrawerFlags() =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun openDrawerFlags() =
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun setDrawerOpen(open: Boolean, requestFocus: Boolean = true) {
        val view = drawerWindow ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        params.flags = if (open) openDrawerFlags() else closedDrawerFlags()
        params.alpha = if (open) 1f else 0f
        runCatching { wm.updateViewLayout(view, params) }

        drawerOpen.value = open
        handleView?.visibility = if (open) View.INVISIBLE else View.VISIBLE

        if (open) {
            // The note may have been edited in the app since the drawer last looked at it.
            reloadBoundBlocks()
            // Focus is bumped once the slide has settled rather than immediately: making the window
            // focusable and asking it to raise the keyboard are two different round trips to the
            // window manager, and the second one loses if it is sent in the same frame as the first.
            //
            // Not when the drawer is opening to show a finished capture: the user is looking at a
            // picture that just landed, and a keyboard over it helps nobody.
            if (requestFocus) {
                mainHandler.removeCallbacks(focusRunnable)
                mainHandler.postDelayed(focusRunnable, FOCUS_DELAY_MS)
            }
        } else {
            mainHandler.removeCallbacks(focusRunnable)
        }
    }

    // ── Pending document ──────────────────────────────────────────────────────────────────────

    private fun persistPending() {
        prefs.overlayPending = encodePending(pendingBlocks.toList())
    }

    private fun clearPending() {
        pendingBlocks.clear()
        pendingBlocks.seedIfEmpty()
        persistPending()
    }

    private fun copyPending() {
        val text = pendingBlocks.filterIsInstance<NoteBlock.Text>().joinToString("\n") { it.text }
        if (text.isBlank()) {
            toast("Nothing to copy")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("WritersBlock", text))
        val images = pendingBlocks.count { it is NoteBlock.Image }
        toast(if (images > 0) "Text copied — $images image(s) stay in the drawer" else "Copied")
    }

    private fun pasteIntoPending() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).text?.toString().orEmpty()
        } else {
            ""
        }
        if (text.isEmpty()) {
            toast("Clipboard is empty")
            return
        }
        pendingBlocks.appendText(text)
        persistPending()
    }

    /** Adds a finished capture: the picture first, then the text that came out of it. */
    private fun appendCapture(text: String, stagedPath: String?) {
        if (stagedPath != null) {
            pendingBlocks.appendImage(alt = captionForCapture(), ref = stagedPath)
        }
        if (text.isNotBlank()) pendingBlocks.appendText(text)
        pendingBlocks.seedIfEmpty()
        persistPending()
        capturedSomething = true
    }

    // ── Binding ───────────────────────────────────────────────────────────────────────────────

    /** Binds the drawer to [path], or to nothing at all when [path] is empty. */
    private fun bind(path: String) {
        prefs.overlayActiveFilePath = path
        boundPath.value = path
        reloadBoundBlocks()
    }

    private fun reloadBoundBlocks() {
        val path = boundPath.value
        boundBlocks = if (path.isEmpty()) {
            emptyList()
        } else {
            val file = File(path)
            runCatching { parseNote(FileRepository.readFile(path), file.name) }.getOrDefault(emptyList())
        }
    }

    // ── Committing ────────────────────────────────────────────────────────────────────────────

    private fun handleSave(request: SaveRequest) {
        when (request) {
            SaveRequest.AppendToBound -> appendToBound()
            SaveRequest.ReplaceBound -> replaceBound()
            is SaveRequest.AsNewNote -> saveAsNewNote(request.folderPath, request.name)
        }
    }

    private fun appendToBound() {
        val path = boundPath.value
        val noteFile = if (path.isEmpty()) null else File(path)
        if (noteFile == null) {
            toast("Bind the drawer to a note first, or save as a new one")
            return
        }

        val merged = joinForAppend(
            FileRepository.readFile(path),
            serializeNote(materialize(noteFile)),
        )
        if (FileRepository.writeFile(path, merged)) {
            clearPending()
            reloadBoundBlocks()
            toast("Appended to ${noteFile.name}")
        } else {
            toast("Could not write ${noteFile.name}")
        }
    }

    private fun replaceBound() {
        val path = boundPath.value
        val noteFile = if (path.isEmpty()) null else File(path)
        if (noteFile == null) {
            toast("Bind the drawer to a note first")
            return
        }

        if (FileRepository.writeFile(path, serializeNote(materialize(noteFile)))) {
            clearPending()
            reloadBoundBlocks()
            toast("Replaced ${noteFile.name}")
        } else {
            toast("Could not write ${noteFile.name}")
        }
    }

    private fun saveAsNewNote(folderPath: String?, name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            toast("Give the note a name")
            return
        }

        // Created first and written second, because the captures have to move into a directory named
        // after the note — which needs the note to exist with its final name.
        val created = FileRepository.createFile(this, folderPath, trimmedName, "")
        if (created == null) {
            toast("Could not create the note")
            return
        }

        val content = serializeNote(materialize(created))
        if (FileRepository.writeFile(created.absolutePath, content)) {
            clearPending()
            bind(created.absolutePath)
            toast("Saved as ${created.name}")
        } else {
            toast("Could not write ${created.name}")
        }
    }

    /**
     * Moves every staged capture into [noteFile]'s assets directory and rewrites the references.
     *
     * A capture whose file has gone is dropped rather than written as a reference to nothing.
     */
    private fun materialize(noteFile: File): List<NoteBlock> =
        materializeBlocks(pendingBlocks.toList()) { staged ->
            val source = File(staged)
            if (!source.isFile) {
                null
            } else {
                FileRepository.moveIntoAssets(noteFile, source)
                    ?.let { FileRepository.refFor(noteFile, it) }
            }
        }

    // ── Capture ───────────────────────────────────────────────────────────────────────────────

    private fun startCapture() {
        wasOpenBeforeCapture = drawerOpen.value
        hideForCapture()

        // A short pause after the overlay goes: the window has to actually leave the composited
        // output before the screenshot is taken, or the drawer ends up in its own capture.
        postDelayed(180) {
            val intent = Intent(this, CaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            runCatching { startActivity(intent) }.onFailure {
                toast("Could not start the capture screen")
                restoreAfterCapture()
            }
        }
    }

    private fun hideForCapture() {
        val view = drawerWindow ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        params.flags = closedDrawerFlags()
        params.alpha = 0f
        runCatching { wm.updateViewLayout(view, params) }
        drawerOpen.value = false
        handleView?.visibility = View.GONE
    }

    /** Puts the overlay back the way it was before a capture, once the capture screen has closed. */
    private fun restoreAfterCapture() {
        handleView?.visibility = View.VISIBLE

        if (capturedSomething) {
            // A capture is the one thing that puts content in the drawer without the user having been
            // in it. Left closed, a scan taken from a closed drawer showed a crop screen, flashed a
            // toast and then nothing at all — indistinguishable from the scan failing.
            capturedSomething = false
            wasOpenBeforeCapture = false
            setDrawerOpen(true, requestFocus = false)
        } else if (wasOpenBeforeCapture) {
            wasOpenBeforeCapture = false
            setDrawerOpen(true)
        }
    }

    private fun postDelayed(delayMillis: Long, block: () -> Unit) =
        Handler(Looper.getMainLooper()).postDelayed(block, delayMillis)

    // ── Foreground service ────────────────────────────────────────────────────────────────────

    /**
     * Android 14 refuses `MediaProjectionManager.getMediaProjection` unless a foreground service of
     * type `mediaProjection` is already running — and refuses to start one of that type before the
     * user has consented. So the type is raised for the duration of a capture and dropped afterwards,
     * rather than being held from process start.
     */
    private fun raiseCaptureForegroundType() {
        if (onCaptureType) return
        onCaptureType = true
        startForegroundCompat(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    private fun lowerCaptureForegroundType() {
        if (!onCaptureType) return
        onCaptureType = false
        startForegroundCompat(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun startForegroundCompat(type: Int) {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            // A type the platform will not grant is not worth crashing the overlay over; the service
            // is already foreground from the plain start, so the notification stays.
            runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), 0) }
        }
    }

    private fun removeAllViews() {
        listOf(handleView, drawerWindow).forEach { view ->
            view?.let { runCatching { wm.removeView(it) } }
        }
        handleView = null
        drawerWindow = null
        viewsReady = false
        drawerOpen.value = false
    }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        overlayWindowType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    /** TYPE_APPLICATION_OVERLAY only exists from API 26; below it, TYPE_PHONE is the overlay type. */
    @Suppress("DEPRECATION")
    private fun overlayWindowType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "WritersBlock Overlay", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the overlay drawer active in the background" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val togglePi = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).apply { action = ACTION_TOGGLE_DRAWER },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("The scroll is ready")
            .setContentText("Tap to unroll the WritersBlock drawer")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .setContentIntent(togglePi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
