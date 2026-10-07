package com.example.writersblock.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.writersblock.data.CaptureStore
import com.example.writersblock.theme.BorderGray
import com.example.writersblock.theme.Corner
import com.example.writersblock.theme.Gap
import com.example.writersblock.theme.PureWhite
import com.example.writersblock.theme.SlateRaised
import com.example.writersblock.theme.TextGray
import com.example.writersblock.theme.TextMuted
import com.example.writersblock.theme.TextWhite
import com.example.writersblock.theme.WritersBlockTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer

/**
 * Set by [OverlayService.startCapture] on the intent that starts a capture, and on no other intent
 * this screen is given.
 *
 * [CaptureActivity.onNewIntent] sees every intent that reaches the activity, including the one it
 * sends itself to come back to the front after consent. Only the one carrying this is a request for a
 * new capture; without the marker, coming to the front would ask for consent again, for ever.
 */
internal const val EXTRA_NEW_CAPTURE = "com.example.writersblock.extra.NEW_CAPTURE"

/**
 * Screenshots the screen, lets the user crop a region, and keeps the cropped picture, optionally with
 * OCR text read from it.
 *
 * The picture is the point. OCR text alone is a lossy copy of whatever was on screen — wrong on
 * handwriting, wrong on code, wrong on anything in a language the recogniser was not trained on — and
 * the cropped bitmap already exists by the time the text does. So [CropAction.IMAGE_ONLY] is a normal
 * outcome rather than a cut-down one. [CaptureStore.stage] writes the bitmap to staging, the drawer
 * keeps it, and committing moves it into the note's assets directory.
 *
 * Three things have to be true for the projection to work at all on a current Android:
 *
 * - a foreground service of type `mediaProjection` must already be running when `getMediaProjection`
 *   is called ([OverlayService.onCaptureStarted] raises it, and it can only be raised after consent),
 * - a `MediaProjection.Callback` must be registered before `createVirtualDisplay`, or the call throws,
 * - the virtual display must be the size of the real display, not of this activity's window, which is
 *   inset by the system bars. Sizing it to the window produces a letterboxed, mis-scaled capture.
 *
 * And one thing has to be true of the window itself: it must be transparent. The frame is taken while
 * this activity is on screen, so an opaque one is what the mirror captures — see the note on the theme
 * in `values/themes.xml`, where that is not merely a matter of setting the attribute.
 */
class CaptureActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Guards the one-shot hand-back to the overlay, so a back press and a finish cannot both fire it. */
    private var closed = false

    /** Set once the crop screen is up, so a second capture request is not mistaken for a new one. */
    private var cropShown = false

    /**
     * The newest frame the mirror has produced.
     *
     * Every frame replaces it and none is used until [settle] fires. The first frame or two after a
     * mirror starts can be partially composited — the previous screen mid-teardown, or the permission
     * dialog still on its way out — and those are exactly the frames the capture would get if the
     * first one available were used.
     */
    private var latestFrame: Bitmap? = null
    private var settleScheduled = false

    /** How many blank frames have been looked past for this capture. See [settle]. */
    private var blankRetries = 0

    /** True once the crop is confirmed and the picture is being made, so the screen can say what it is doing. */
    private val working = mutableStateOf<CropAction?>(null)

    private val frameTimeout = Runnable {
        if (closed) return@Runnable
        toast("The screen produced no image. Nothing was captured.")
        finishCapture()
    }

    /**
     * Takes the newest frame once the mirror has had a moment to settle.
     *
     * Scheduled by the *first* frame rather than at projection start, so a slow start delays the
     * capture instead of being indistinguishable from a failure.
     */
    private val settle = Runnable { takeFrame() }

    private fun takeFrame() {
        if (closed) return
        val bitmap = latestFrame ?: return

        // A frame grabbed while the mirror is still coming up — the consent dialog on its way out, the
        // window not yet composited — is black, and taking it is a scan that reports success and keeps
        // a picture of nothing. Nothing distinguishes that frame from a genuinely black screen, so it
        // is set aside and the mirror is looked at again. After a few tries it is accepted: by then the
        // screen really is black, and the crop screen says so rather than waiting for a frame that is
        // never going to be different.
        if (blankRetries < BLANK_RETRIES && CaptureStore.looksBlank(bitmap)) {
            blankRetries++
            mainHandler.postDelayed(settle, SETTLE_MS)
            return
        }

        latestFrame = null
        mainHandler.removeCallbacks(frameTimeout)
        stopProjection()
        showCropScreen(bitmap)
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user or the system revoked the projection. If a frame already came through, [settle]
            // is going to use it; otherwise let the capture fail visibly rather than time out.
            if (latestFrame == null && !cropShown) {
                stopProjection()
                toast("Screen capture was stopped")
                finishCapture()
            }
        }
    }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                // Android 14 will not hand over a projectable MediaProjection unless a foreground
                // service of type mediaProjection is already running, and will not let one start
                // before the user has consented — so this is the earliest it can be raised.
                OverlayService.onCaptureStarted()
                // Give the consent dialog time to leave the composited output before grabbing a frame.
                mainHandler.postDelayed({ startScreenCapture(result.resultCode, data) }, 350)
            } else {
                toast("Screen capture permission denied")
                finishCapture()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // So the crop screen can draw over the whole screen rather than inside the system bars.
        enableEdgeToEdge()

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    // ── Projection ────────────────────────────────────────────────────────────────────────────

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        val (width, height) = displaySize()
        val density = resources.displayMetrics.densityDpi

        // A second request can arrive while the first is still in flight. Let the old projection and
        // its frame go first: a frame left over from it is the newest one available, so the new capture
        // would otherwise be of the screen as it was before, or of the consent dialog. The callback is
        // unregistered on the way out, so stopping the old projection cannot be mistaken for the user
        // revoking the new one.
        stopProjection()
        latestFrame?.recycle()
        latestFrame = null

        // This instance may have captured before: `singleTop` reuses it for a second request rather
        // than building a new one, and neither of these describes the activity, only the capture
        // that is being taken now.
        settleScheduled = false
        blankRetries = 0

        try {
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            // Registered *before* the display exists, which is the whole fix. A mirrored screen that
            // is not changing produces one frame and then nothing: a listener attached after the
            // display was created can miss that only frame and then wait forever for a second one that
            // never arrives. Observably that was a four-second pause and "the screen produced no
            // image" — on precisely the screens worth scanning, which are the still ones.
            reader.setOnImageAvailableListener({ onFrame(it) }, mainHandler)

            val projection = projectionManager.getMediaProjection(resultCode, data)
            if (projection == null) {
                toast("Could not start the screen capture")
                finishCapture()
                return
            }
            mediaProjection = projection

            // Required before createVirtualDisplay on target 34+. Without it the call throws
            // IllegalStateException("Must register a callback before starting capture"), which is
            // exactly what a capture that never opens the crop screen looks like from outside.
            projection.registerCallback(projectionCallback, mainHandler)

            virtualDisplay = projection.createVirtualDisplay(
                "WritersBlockCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null, null
            )
        } catch (e: Exception) {
            toast(
                if (e is SecurityException) {
                    "Android refused the capture. Is the overlay service still running?"
                } else {
                    "Screen capture failed: ${e.localizedMessage ?: e::class.java.simpleName}"
                }
            )
            finishCapture()
            return
        }

        mainHandler.postDelayed(frameTimeout, FRAME_TIMEOUT_MS)
    }

    /**
     * The size of the display, not of this window.
     *
     * `resources.displayMetrics` describes the activity's own window, which the system insets to keep
     * clear of the status and navigation bars. A virtual display created at that size mirrors a
     * smaller region than the screen and everything in the capture is scaled.
     */
    private fun displaySize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }

        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
            ?: return resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels

        val point = android.graphics.Point()
        @Suppress("DEPRECATION")
        display.getRealSize(point)
        return point.x to point.y
    }

    private fun onFrame(reader: ImageReader) {
        if (closed) return

        val image = reader.acquireLatestImage() ?: return
        val bitmap = try {
            processImage(image)
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
        if (bitmap == null) return

        latestFrame?.recycle()
        latestFrame = bitmap

        if (!settleScheduled) {
            settleScheduled = true
            mainHandler.postDelayed(settle, SETTLE_MS)
        }
    }

    private fun processImage(image: Image): Bitmap? {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        // The buffer is padded out to the row stride, so it is read into a bitmap that is wide enough
        // to hold the padding and then trimmed back to the real width.
        val padded = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
        }
    }

    private fun stopProjection() {
        mainHandler.removeCallbacks(frameTimeout)
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { virtualDisplay?.release() }
        runCatching { mediaProjection?.stop() }
        imageReader?.close()
        virtualDisplay = null
        mediaProjection = null
        imageReader = null
    }

    // ── Crop, keep, read ──────────────────────────────────────────────────────────────────────

    private fun showCropScreen(bitmap: Bitmap) {
        cropShown = true
        // Android 14's "share a single app" option brings the app the user picked to the front to
        // confirm it, which leaves this screen — and so the crop — behind it. Coming back here puts
        // the crop where the user is already looking, instead of leaving them to find their way back
        // to it. Done now rather than at consent, because until the frame has been taken this screen
        // has to stay out of the way of whatever is being captured.
        bringToFront()
        setContent {
            WritersBlockTheme {
                CropScreen(
                    bitmap = bitmap,
                    looksBlank = remember(bitmap) { CaptureStore.looksBlank(bitmap) },
                    working = working.value,
                    onCropConfirmed = { rect, action -> keepCrop(bitmap, rect, action) },
                    onCancelled = { finishCapture() },
                )
            }
        }
    }

    /**
     * Puts this screen back in front of whatever the consent dialog left on top of it.
     *
     * Sent to itself: `singleTop` reuses this instance, so it comes back as [onNewIntent]. That is why
     * the request carries no [EXTRA_NEW_CAPTURE] — arriving without it, it cannot ask for consent a
     * second time. A refusal from the platform is not worth reporting: the crop is still there, one
     * back gesture away.
     */
    private fun bringToFront() {
        val intent = Intent(this, CaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        runCatching { startActivity(intent) }
    }

    /**
     * Crops, shrinks and stages, then reads the text too if that is what was asked for.
     *
     * The staged file is the one the note will end up holding, and it exists before the recogniser is
     * consulted: the picture is the capture, and the reading is a lossy copy of it that can fail on its
     * own without taking the picture with it.
     */
    private fun keepCrop(screenshot: Bitmap, rect: android.graphics.Rect, action: CropAction) {
        if (working.value != null) return
        working.value = action

        val cropped = try {
            Bitmap.createBitmap(screenshot, rect.left, rect.top, rect.width(), rect.height())
        } catch (e: Exception) {
            null
        }

        if (cropped == null) {
            working.value = null
            toast("Cropping failed")
            return
        }

        val kept = CaptureStore.downscale(cropped)
        val staged = CaptureStore.stage(this, kept)

        if (action == CropAction.IMAGE_ONLY) {
            // Nothing to read and so nothing to fall back on: without the file there is no capture.
            if (staged == null) {
                working.value = null
                toast("Could not save the captured image")
                return
            }
            deliver("", staged.absolutePath)
            return
        }

        if (staged == null) {
            // The picture is the point, but text with no picture still beats losing both.
            toast("Could not save the captured image")
        }

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(kept, 0))
            .addOnSuccessListener { visionText ->
                if (visionText.text.isBlank() && staged != null) {
                    toast("No text found — the picture was kept")
                }
                deliver(visionText.text, staged?.absolutePath)
            }
            .addOnFailureListener { e ->
                toast("Text recognition failed: ${e.localizedMessage ?: "unknown error"}")
                // The picture is still worth keeping even when the reading was not.
                deliver("", staged?.absolutePath)
            }
            .addOnCompleteListener {
                runCatching { recognizer.close() }
            }
    }

    private fun deliver(text: String, stagedPath: String?) {
        OverlayService.deliverCapture(text, stagedPath)
        finishCapture()
    }

    /** Hands control back to the overlay exactly once, then finishes. */
    private fun finishCapture() {
        if (closed) return
        closed = true
        stopProjection()
        OverlayService.onCaptureEnded()
        OverlayService.onCaptureClosed()
        finish()
    }

    /**
     * `launchMode` is `singleTop`, so a capture request that arrives while this one is still alive
     * comes here rather than to [onCreate]. Without this the second request would do nothing at all.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Already cropping: a request while the user is choosing a region is not a new capture. The
        // extra is what separates a real request from this screen's own [bringToFront].
        if (intent.getBooleanExtra(EXTRA_NEW_CAPTURE, false) && !cropShown) {
            captureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    override fun onDestroy() {
        stopProjection()
        mainHandler.removeCallbacks(settle)
        // Only ever the frame that was never handed to the crop screen; [settle] nulls it on the way
        // out, so this cannot recycle a bitmap that is on screen.
        latestFrame?.recycle()
        latestFrame = null
        // Finished by a back press rather than through finishCapture: the overlay is still hidden and
        // still holding the mediaProjection foreground type, so it has to be let go here too.
        if (!closed) {
            closed = true
            OverlayService.onCaptureEnded()
            OverlayService.onCaptureClosed()
        }
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val FRAME_TIMEOUT_MS = 5000L

        /** How long the mirror is given to settle after its first frame before the capture is taken. */
        const val SETTLE_MS = 350L

        /** How many blank frames are looked past before one is accepted as what the screen shows. */
        const val BLANK_RETRIES = 3
    }
}

/**
 * What the user asked to keep.
 *
 * Two actions rather than a "read the text too" boolean, because the confirm row is two buttons and
 * the outcome is being chosen at the moment they are pressed — and a bare `true` at a call site says
 * nothing about which button it came from.
 */
enum class CropAction {
    /** The picture alone. No recogniser, nothing to wait for, nothing that can fail separately. */
    IMAGE_ONLY,

    /** The picture, and the text read from it. */
    IMAGE_AND_TEXT,
}

/**
 * The crop step.
 *
 * The screenshot is scaled to fit the screen and the selection is kept in screen coordinates, then
 * mapped back through the same scale on confirm. Drawing the bitmap at its natural size would be
 * wrong on any device whose display is larger than the activity window: the bottom of the screenshot
 * would be off-screen, uncroppable, and the coordinate mapping would silently disagree with what the
 * user could see.
 *
 * FULL SCREEN exists because dragging is the slow path and scanning a whole screen is the common case.
 * It selects the entire screenshot, which is still exactly the same code path as a drag — one rect,
 * confirmed.
 *
 * Keeping the picture on its own is a first-class outcome, not the poor relation of keeping it with
 * the text: the cropped bitmap is the capture, and the reading is a copy of it that is wrong on
 * handwriting, code and any language the recogniser was not trained on. When that is what is wanted,
 * IMAGE ONLY is a normal button next to the other one, and the path behind it never starts a
 * recogniser at all.
 *
 * @param working the action in flight, or null when the screen is still waiting for a selection. While
 *   it is set the screen is inert, so a slow reading cannot be mistaken for a tap that did not register
 *   and repeated.
 * @param onCropConfirmed receives the selection in **bitmap** coordinates and which of the two actions
 *   was pressed.
 */
@Composable
fun CropScreen(
    bitmap: Bitmap,
    looksBlank: Boolean,
    working: CropAction?,
    onCropConfirmed: (android.graphics.Rect, CropAction) -> Unit,
    onCancelled: () -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val density = LocalDensity.current

    var start by remember { mutableStateOf<Offset?>(null) }
    var current by remember { mutableStateOf<Offset?>(null) }

    val busy = working != null

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // The screenshot is fitted and centred; all the arithmetic between what the finger touches and
        // what gets cropped lives in CropGeometry, where it can be tested without a screen.
        val geometry = CropGeometry(
            viewWidth = constraints.maxWidth.toFloat(),
            viewHeight = constraints.maxHeight.toFloat(),
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
        )

        val selection: Rect? = if (start != null && current != null) {
            geometry.selectionBetween(start!!, current!!)
        } else {
            null
        }

        // The selection drawn back in screen space, so it sits exactly where the finger went.
        val selectionOnScreen: Rect? = selection?.let { geometry.toScreen(it) }

        val hasSelection = selection != null && selection.width >= 8f && selection.height >= 8f

        /** Selects the whole screenshot. Same code path as a drag: one rect, confirmed. */
        fun selectAll() {
            val (from, to) = geometry.wholeImageDrag()
            start = from
            current = to
        }

        /** The current selection as the rectangle the crop will be cut with, or null if there is none. */
        fun selectedRect(): android.graphics.Rect? = selection?.let {
            android.graphics.Rect(
                it.left.toInt(),
                it.top.toInt(),
                it.right.toInt(),
                it.bottom.toInt(),
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap, geometry, busy) {
                    if (busy) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            start = offset
                            current = offset
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            current = (current ?: Offset.Zero) + dragAmount
                        },
                        onDragEnd = { /* the selection stays until the next drag replaces it */ },
                    )
                }
        ) {
            drawImage(
                image = image,
                srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                srcSize = androidx.compose.ui.unit.IntSize(bitmap.width, bitmap.height),
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    geometry.originX.toInt(),
                    geometry.originY.toInt()
                ),
                dstSize = androidx.compose.ui.unit.IntSize(
                    geometry.drawnWidth.toInt(),
                    geometry.drawnHeight.toInt()
                ),
            )

            val onScreen = selectionOnScreen
            if (onScreen != null) {
                clipRect(
                    left = onScreen.left,
                    top = onScreen.top,
                    right = onScreen.right,
                    bottom = onScreen.bottom,
                    clipOp = ClipOp.Difference,
                ) {
                    drawRect(color = Color(0xA6000000))
                }
                drawRect(
                    color = PureWhite,
                    topLeft = Offset(onScreen.left, onScreen.top),
                    size = Size(onScreen.width, onScreen.height),
                    style = Stroke(width = 1.5f * density.density),
                )
                // Corner ticks, so the selection reads as a crop box rather than as a highlight.
                val tick = 14f * density.density
                val corners = listOf(
                    Offset(onScreen.left, onScreen.top) to Offset(1f, 1f),
                    Offset(onScreen.right, onScreen.top) to Offset(-1f, 1f),
                    Offset(onScreen.left, onScreen.bottom) to Offset(1f, -1f),
                    Offset(onScreen.right, onScreen.bottom) to Offset(-1f, -1f),
                )
                corners.forEach { (corner, direction) ->
                    drawLine(
                        color = PureWhite,
                        start = corner,
                        end = corner + Offset(tick * direction.x, 0f),
                        strokeWidth = 3f * density.density,
                    )
                    drawLine(
                        color = PureWhite,
                        start = corner,
                        end = corner + Offset(0f, tick * direction.y),
                        strokeWidth = 3f * density.density,
                    )
                }
            } else {
                drawRect(color = Color(0x59000000))
            }
        }

        // Foreground: the step prompt, any warning, and the actions.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Gap.screenH, vertical = Gap.screenV),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (looksBlank) {
                    NoticeBox(
                        "THIS SCREEN IS BLACK — apps that block screenshots (banking, some chat " +
                                "and video apps) return a blank frame, so there is nothing to read."
                    )
                    Spacer(Modifier.height(Gap.sm))
                }

                PromptPill(
                    when {
                        working == CropAction.IMAGE_AND_TEXT -> "READING THE TEXT…"
                        working == CropAction.IMAGE_ONLY -> "KEEPING THE PICTURE…"
                        selection == null -> "DRAG OVER WHAT YOU WANT TO KEEP"
                        else -> "${selection.width.toInt()} × ${selection.height.toInt()} PX"
                    }
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                // OCR takes a moment on a dense screen. Without this the only feedback was a button
                // that stopped responding, which is indistinguishable from a tap that missed.
                if (busy) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = Gap.sm),
                        color = PureWhite,
                        trackColor = BorderGray,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
                    OutlineAction(
                        label = "FULL SCREEN",
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        onClick = { selectAll() },
                    )
                    OutlineAction(
                        label = "CANCEL",
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        onClick = onCancelled,
                    )
                }

                Spacer(Modifier.height(Gap.sm))

                // The two ways of keeping the crop, side by side because they are alternatives rather
                // than a default and its lesser twin. The one that also reads is wider. Only the
                // button that was pressed changes its wording — the other just goes inert, so the
                // screen does not say the same thing twice.
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
                    PrimaryAction(
                        label = if (working == CropAction.IMAGE_AND_TEXT) "READING…" else "KEEP + READ",
                        enabled = hasSelection && !busy,
                        modifier = Modifier.weight(1.4f),
                        onClick = { selectedRect()?.let { onCropConfirmed(it, CropAction.IMAGE_AND_TEXT) } },
                    )
                    OutlineAction(
                        label = if (working == CropAction.IMAGE_ONLY) "KEEPING…" else "IMAGE ONLY",
                        enabled = hasSelection && !busy,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedRect()?.let { onCropConfirmed(it, CropAction.IMAGE_ONLY) } },
                    )
                }
            }
        }
    }
}

/** The confirm action: filled, so it is the obvious one to press. */
@Composable
private fun PrimaryAction(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PureWhite,
            contentColor = Color.Black,
            disabledContainerColor = SlateRaised,
            disabledContentColor = TextMuted,
        ),
        shape = RoundedCornerShape(Corner.medium),
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            fontSize = 13.sp,
        )
    }
}

/** The instruction or readout, as a white pill. Legible over any screenshot, dark or light. */
@Composable
private fun PromptPill(text: String) {
    Box(
        modifier = Modifier
            .background(PureWhite, RoundedCornerShape(Corner.small))
            .padding(horizontal = Gap.lg, vertical = Gap.sm)
    ) {
        Text(
            text = text,
            color = Color.Black,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

/** A warning. Dark and outlined, so it reads as a caveat rather than as the instruction. */
@Composable
private fun NoticeBox(text: String) {
    Box(
        modifier = Modifier
            .background(Color(0xE6121212), RoundedCornerShape(Corner.small))
            .border(BorderStroke(1.dp, BorderGray), RoundedCornerShape(Corner.small))
            .padding(horizontal = Gap.lg, vertical = Gap.md)
    ) {
        Text(
            text = text,
            color = TextGray,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp,
        )
    }
}

/** A secondary action: outlined, so the primary one is unambiguous. */
@Composable
private fun OutlineAction(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(46.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = TextWhite,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = TextMuted,
        ),
        border = BorderStroke(1.dp, BorderGray),
        shape = RoundedCornerShape(Corner.medium),
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            fontSize = 11.sp,
        )
    }
}
