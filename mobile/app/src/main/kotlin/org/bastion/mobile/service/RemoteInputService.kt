package org.bastion.mobile.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.getSystemService
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bastion.protocol.v1.GlobalAction
import org.bastion.protocol.v1.RemoteInput

/** A captured screen frame: JPEG bytes and the transmitted pixel size. */
class CapturedScreen(val jpeg: ByteArray, val width: Int, val height: Int)

/**
 * Accessibility service that lets the paired owner see and drive the phone remotely. It both
 * captures the screen (`takeScreenshot`, no per-session consent dialog, unlike MediaProjection)
 * and injects taps, swipes, text and navigation. The OS shows an accessibility indicator while it
 * is enabled. It is reached only through the end-to-end-authenticated [ProtectionService]; it
 * exposes nothing exported and holds no network access of its own. Input is never dispatched while
 * a secure keyguard is showing (the caller enforces this) — the lock-screen PIN is never bypassed.
 */
@Suppress("TooManyFunctions")
class RemoteInputService : AccessibilityService() {
    @Volatile private var keepAwakeLock: PowerManager.WakeLock? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    // The framework requires these overrides; this service is driven on demand, not by events.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        releaseKeepAwake()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        releaseKeepAwake()
        instance = null
        super.onDestroy()
    }

    /** Whether screen capture is available (needs Android 11+). */
    fun canCapture(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Captures the current display, downscaled to [maxEdgePx] and JPEG-encoded so the result stays
     * under [MAX_FRAME_BYTES] (the protocol field limit): quality is stepped down first, then the
     * image is scaled down if a dense screen is still too large.
     */
    @Suppress("ReturnCount", "SwallowedException")
    suspend fun capture(maxEdgePx: Int, quality: Int): CapturedScreen? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val bitmap = takeScreenshotBitmap() ?: return null
        return try {
            encodeUnderLimit(bitmap, maxEdgePx, quality)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "screenshot encode OOM")
            null
        } finally {
            bitmap.recycle()
        }
    }

    // Encodes [source] (never recycled here — the caller owns it); recycles every scaled copy it
    // makes. Steps quality down, then dimensions, until the JPEG fits the protocol field limit.
    private fun encodeUnderLimit(source: Bitmap, maxEdgePx: Int, quality: Int): CapturedScreen {
        var current = scaleToEdge(source, maxEdgePx)
        var q = quality
        while (true) {
            val out = ByteArrayOutputStream()
            current.compress(Bitmap.CompressFormat.JPEG, q, out)
            val bytes = out.toByteArray()
            val minimal = q <= MIN_QUALITY && current.width <= MIN_DIMENSION
            if (bytes.size <= MAX_FRAME_BYTES || minimal) {
                val result = CapturedScreen(bytes, current.width, current.height)
                if (current !== source) current.recycle()
                return result
            }
            if (q > MIN_QUALITY) {
                q -= QUALITY_STEP
            } else {
                val next = current.scale(
                    (current.width * SCALE_STEP).toInt().coerceAtLeast(MIN_DIMENSION),
                    (current.height * SCALE_STEP).toInt().coerceAtLeast(MIN_DIMENSION),
                )
                if (current !== source) current.recycle()
                current = next
            }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private suspend fun takeScreenshotBitmap(): Bitmap? = suspendCancellableCoroutine { cont ->
        val executor = mainExecutor
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val bitmap = runCatching {
                            Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        buffer.close()
                        if (cont.isActive) cont.resume(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        // ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT (too frequent) and transient
                        // errors are simply dropped; the caller paces and retries.
                        Log.d(TAG, "screenshot failed: $errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "takeScreenshot threw: ${e.javaClass.simpleName}")
            if (cont.isActive) cont.resume(null)
        }
    }

    /** Injects one input action. Returns false when it could not be dispatched. */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun inject(input: RemoteInput): Boolean = when (input.actionCase) {
        RemoteInput.ActionCase.TAP -> {
            val size = displaySize()
            tap(input.tap.x * size.x, input.tap.y * size.y, input.tap.longPress)
        }

        RemoteInput.ActionCase.SWIPE -> {
            val size = displaySize()
            swipe(
                input.swipe.x1 * size.x,
                input.swipe.y1 * size.y,
                input.swipe.x2 * size.x,
                input.swipe.y2 * size.y,
                input.swipe.durationMs.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS).toLong(),
            )
        }

        RemoteInput.ActionCase.TEXT -> typeText(input.text.text, input.text.submit)

        RemoteInput.ActionCase.GLOBAL -> global(input.global)

        else -> false
    }

    private fun tap(x: Float, y: Float, longPress: Boolean): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val duration = if (longPress) LONG_PRESS_MS else TAP_MS
        return dispatch(GestureDescription.StrokeDescription(path, 0, duration))
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(GestureDescription.StrokeDescription(path, 0, durationMs))
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun global(action: GlobalAction): Boolean = when (action) {
        GlobalAction.GLOBAL_ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        GlobalAction.GLOBAL_ACTION_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        GlobalAction.GLOBAL_ACTION_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        GlobalAction.GLOBAL_ACTION_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        GlobalAction.GLOBAL_ACTION_QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        GlobalAction.GLOBAL_ACTION_WAKE -> wake()
        GlobalAction.GLOBAL_ACTION_LOCK -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        else -> false
    }

    @Suppress("ReturnCount")
    private fun typeText(text: String, submit: Boolean): Boolean {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!node.isEditable) return false
        val existing = node.text?.toString().orEmpty()
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
        }
        val set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (submit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return set
    }

    /** Best-effort wake of a sleeping display. Never unlocks the keyguard. */
    @Suppress("DEPRECATION", "WakelockTimeout")
    private fun wake(): Boolean {
        val power = getSystemService<PowerManager>() ?: return false
        if (power.isInteractive) return true
        val lock = power.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "bastion:wake",
        )
        return runCatching {
            lock.acquire(WAKE_MS)
            true
        }.getOrDefault(false)
    }

    /** Holds a screen wake lock while a mirror session wants the display kept on. */
    @Suppress("DEPRECATION", "WakelockTimeout")
    fun setKeepAwake(enabled: Boolean) {
        if (enabled) {
            if (keepAwakeLock != null) return
            val power = getSystemService<PowerManager>() ?: return
            keepAwakeLock = power.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "bastion:keepAwake",
            ).apply { runCatching { acquire(KEEP_AWAKE_MS) } }
        } else {
            releaseKeepAwake()
        }
    }

    private fun releaseKeepAwake() {
        runCatching { keepAwakeLock?.takeIf { it.isHeld }?.release() }
        keepAwakeLock = null
    }

    private fun displaySize(): Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService<WindowManager>()?.maximumWindowMetrics?.bounds
            if (bounds != null) return Point(bounds.width(), bounds.height())
        }
        val metrics = resources.displayMetrics
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    private fun scaleToEdge(bitmap: Bitmap, maxEdgePx: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdgePx) return bitmap
        val factor = maxEdgePx.toFloat() / longest
        val width = (bitmap.width * factor).toInt().coerceAtLeast(1)
        val height = (bitmap.height * factor).toInt().coerceAtLeast(1)
        return bitmap.scale(width, height)
    }

    companion object {
        private const val TAG = "RemoteInputService"
        private const val TAP_MS = 1L
        private const val LONG_PRESS_MS = 600L
        private const val MIN_SWIPE_MS = 20
        private const val MAX_SWIPE_MS = 3000
        private const val WAKE_MS = 10_000L
        private const val KEEP_AWAKE_MS = 15 * 60_000L

        // Keep each frame under the protocol field limit (384 KiB) with margin for envelope overhead.
        private const val MAX_FRAME_BYTES = 360 * 1024
        private const val MIN_QUALITY = 30
        private const val QUALITY_STEP = 15
        private const val SCALE_STEP = 0.8f
        private const val MIN_DIMENSION = 320

        /** Set while the service is connected (i.e. the owner enabled it), else null. */
        @Volatile
        var instance: RemoteInputService? = null
            private set
    }
}
