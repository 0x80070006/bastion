package org.bastion.mobile.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bastion.protocol.v1.CapturePhoto

/** A captured, re-encoded JPEG with its pixel dimensions. */
class CapturedImage(val jpeg: ByteArray, val width: Int, val height: Int)

/**
 * Background JPEG capture with CameraX, without a preview surface. The OS camera indicator is
 * always shown while a session is bound. Captured frames are downscaled and rotated to the
 * requested longest edge and re-encoded, bounding their size well under the 384 KiB field limit.
 */
class CameraCapture(private val context: Context) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var maxEdge: Int = DEFAULT_EDGE

    override val lifecycle: Lifecycle get() = registry

    /** Binds the chosen camera. Returns false if the camera is unavailable. */
    suspend fun start(cameraValue: Int, maxEdgePx: Int): Boolean = withContext(Dispatchers.Main) {
        maxEdge = maxEdgePx.coerceIn(MIN_EDGE, MAX_EDGE)
        val selector = when (cameraValue) {
            CapturePhoto.Camera.CAMERA_FRONT_VALUE -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }
        val cameraProvider = runCatching { awaitProvider() }.getOrNull() ?: return@withContext false
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        try {
            registry.currentState = Lifecycle.State.RESUMED
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this@CameraCapture, selector, capture)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "camera bind failed: ${e.javaClass.simpleName}")
            registry.currentState = Lifecycle.State.CREATED
            return@withContext false
        }
        provider = cameraProvider
        imageCapture = capture
        true
    }

    /** Captures and re-encodes one frame, or null on failure. */
    suspend fun capture(): CapturedImage? {
        val capture = imageCapture ?: return null
        val image = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine<ImageProxy?> { continuation ->
                capture.takePicture(
                    executor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            if (continuation.isActive) continuation.resume(image) else image.close()
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.d(TAG, "capture error: ${exception.imageCaptureError}")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }
        } ?: return null
        return image.use { reencode(it) }
    }

    /** Unbinds the camera and releases resources. */
    suspend fun stop() = withContext(Dispatchers.Main) {
        runCatching { provider?.unbindAll() }
        provider = null
        imageCapture = null
        registry.currentState = Lifecycle.State.DESTROYED
    }

    fun shutdown() {
        executor.shutdown()
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching { future.get() }
                .onSuccess { if (cont.isActive) cont.resume(it) }
                .onFailure { cont.cancel(it) }
        }, ContextCompat.getMainExecutor(context))
    }

    // A frame that fails to decode is dropped, not propagated.
    @Suppress("SwallowedException", "ReturnCount")
    private fun reencode(image: ImageProxy): CapturedImage? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), maxEdge)
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "decode OOM")
            null
        } ?: return null
        val scaled = scaleAndRotate(decoded, image.imageInfo.rotationDegrees)
        if (scaled !== decoded) decoded.recycle()
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val result = CapturedImage(out.toByteArray(), scaled.width, scaled.height)
        scaled.recycle()
        return result
    }

    private fun scaleAndRotate(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        val factor = if (longest > maxEdge) maxEdge.toFloat() / longest else 1f
        if (factor == 1f && rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply {
            if (factor != 1f) postScale(factor, factor)
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun sampleSize(longestEdge: Int, target: Int): Int {
        var sample = 1
        while (longestEdge / (sample * 2) >= target) sample *= 2
        return sample
    }

    private companion object {
        const val TAG = "CameraCapture"
        const val DEFAULT_EDGE = 640
        const val MIN_EDGE = 240
        const val MAX_EDGE = 1280
        const val JPEG_QUALITY = 70
        const val CAPTURE_TIMEOUT_MS = 8_000L
    }
}
