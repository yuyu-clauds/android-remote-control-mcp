package com.danielealbano.androidremotecontrolmcp.services.screencapture

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.services.accessibility.SensitivePageDetector
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Production implementation of [ScreenCaptureProvider] that uses
 * AccessibilityService.takeScreenshot() for capturing screenshots.
 *
 * This approach does NOT require user consent or additional permissions beyond
 * the accessibility service being enabled with canTakeScreenshot="true".
 *
 * This class is Hilt-injectable and stateless.
 */
class ScreenCaptureProviderImpl
    @Inject
    constructor(
        private val screenshotEncoder: ScreenshotEncoder,
        private val apiLevelProvider: ApiLevelProvider,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) : ScreenCaptureProvider {
        private sealed class ServiceValidation {
            data class Valid(
                val service: McpAccessibilityService,
            ) : ServiceValidation()

            data class Invalid(
                val error: McpToolException,
            ) : ServiceValidation()
        }

        @Suppress("ReturnCount")
        override suspend fun captureScreenshot(
            quality: Int,
            maxWidth: Int?,
            maxHeight: Int?,
        ): Result<ScreenshotData> {
            val validation = validateService()
            if (validation is ServiceValidation.Invalid) {
                return Result.failure(validation.error)
            }
            val service = (validation as ServiceValidation.Valid).service

            if (isCurrentScreenSensitive()) {
                Log.i(TAG, "Sensitive page detected — returning placeholder screenshot")
                return Result.success(buildPlaceholderScreenshotData(quality))
            }

            val bitmap =
                service.takeScreenshotBitmap()
                    ?: return Result.failure(
                        McpToolException.ActionFailed("Screenshot capture failed or timed out"),
                    )

            var resizedBitmap: Bitmap? = null
            return try {
                resizedBitmap = screenshotEncoder.resizeBitmapProportional(bitmap, maxWidth, maxHeight)
                val screenshotData = screenshotEncoder.bitmapToScreenshotData(resizedBitmap, quality)
                Result.success(screenshotData)
            } finally {
                // resizeBitmapProportional returns the SAME bitmap if no resize needed,
                // or a NEW bitmap if resized. Recycle the resized one first (if different),
                // then always recycle the original.
                if (resizedBitmap != null && resizedBitmap !== bitmap) {
                    resizedBitmap.recycle()
                }
                bitmap.recycle()
            }
        }

        @SuppressLint("NewApi")
        @Suppress("ReturnCount", "TooGenericExceptionCaught")
        override suspend fun captureScreenshotBitmap(
            maxWidth: Int?,
            maxHeight: Int?,
        ): Result<Bitmap> {
            val validation = validateService()
            if (validation is ServiceValidation.Invalid) {
                return Result.failure(validation.error)
            }
            val service = (validation as ServiceValidation.Valid).service

            if (isCurrentScreenSensitive()) {
                Log.i(TAG, "Sensitive page detected — returning placeholder bitmap")
                return Result.success(buildPlaceholderBitmap())
            }

            val bitmap =
                service.takeScreenshotBitmap()
                    ?: return Result.failure(
                        McpToolException.ActionFailed("Screenshot capture failed or timed out"),
                    )

            return try {
                val resizedBitmap = screenshotEncoder.resizeBitmapProportional(bitmap, maxWidth, maxHeight)
                // If resize produced a new bitmap, recycle the original
                if (resizedBitmap !== bitmap) {
                    bitmap.recycle()
                }
                Result.success(resizedBitmap)
            } catch (e: Exception) {
                // Ensure original bitmap is recycled even if resize throws
                bitmap.recycle()
                Log.e(TAG, "Screenshot resize failed", e)
                Result.failure(
                    McpToolException.ActionFailed("Screenshot resize failed"),
                )
            }
        }

        @Suppress("ReturnCount")
        private fun validateService(): ServiceValidation {
            if (apiLevelProvider.getSdkInt() < API_LEVEL_R) {
                return ServiceValidation.Invalid(
                    McpToolException.PermissionDenied(
                        "Screenshot capture requires Android 11 (API 30) or higher",
                    ),
                )
            }
            if (!accessibilityServiceProvider.isReady()) {
                return ServiceValidation.Invalid(
                    McpToolException.PermissionDenied(
                        "Accessibility service not enabled. Please enable it in Android Settings.",
                    ),
                )
            }
            val service =
                accessibilityServiceProvider.getContext() as? McpAccessibilityService
                    ?: return ServiceValidation.Invalid(
                        McpToolException.PermissionDenied(
                            "Accessibility service not enabled. Please enable it in Android Settings.",
                        ),
                    )
            if (!service.canTakeScreenshot()) {
                return ServiceValidation.Invalid(
                    McpToolException.PermissionDenied(
                        "Screenshot capability not available on this device",
                    ),
                )
            }
            return ServiceValidation.Valid(service)
        }

        @Suppress("ReturnCount")
        override fun isScreenCaptureAvailable(): Boolean {
            if (!accessibilityServiceProvider.isReady()) return false
            val service = accessibilityServiceProvider.getContext() as? McpAccessibilityService ?: return false
            return apiLevelProvider.getSdkInt() >= API_LEVEL_R && service.canTakeScreenshot()
        }

        /**
         * Checks the current foreground screen against [SensitivePageDetector]. Best-effort:
         * exceptions from the accessibility framework (stale node, service interrupted)
         * are swallowed and treated as "not sensitive" — privacy isn't worse than the
         * pre-existing screenshot path, and we don't want to break capture on a flake.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun isCurrentScreenSensitive(): Boolean =
            try {
                val pkg = accessibilityServiceProvider.getCurrentPackageName()
                val root = accessibilityServiceProvider.getRootNode()
                SensitivePageDetector.isSensitiveScreen(root, pkg)
            } catch (t: Throwable) {
                Log.w(TAG, "Sensitive-page check failed; treating as non-sensitive", t)
                false
            }

        /** Solid-color 8x8 bitmap used as a stand-in when the real screen is sensitive. */
        private fun buildPlaceholderBitmap(): Bitmap {
            val bmp = Bitmap.createBitmap(PLACEHOLDER_DIM, PLACEHOLDER_DIM, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.BLACK)
            // No need to draw text — at 8x8 it wouldn't render. The placeholder string is
            // surfaced via logs and via the caller's text channel when applicable.
            return bmp
        }

        /** JPEG-encoded version of [buildPlaceholderBitmap] wrapped in [ScreenshotData]. */
        private fun buildPlaceholderScreenshotData(quality: Int): ScreenshotData {
            val bmp = buildPlaceholderBitmap()
            return try {
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                ScreenshotData(data = b64, width = PLACEHOLDER_DIM, height = PLACEHOLDER_DIM)
            } finally {
                bmp.recycle()
            }
        }

        companion object {
            private const val TAG = "MCP:ScreenCapture"

            /** Android 11 (API 30) — minimum for AccessibilityService.takeScreenshot(). */
            private const val API_LEVEL_R = 30

            /** Pixel dimensions of the sensitive-page placeholder bitmap. */
            private const val PLACEHOLDER_DIM = 8
        }
    }
