package com.example.heyandroid.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.heyandroid.R
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Captures one frame of the screen and saves it, so the assistant can attach what
 * the user is looking at without hunting for a gallery screenshot.
 *
 * MediaProjection requires a foreground service on Android 10+, and the consent
 * dialog is owned by the system: the activity launches the projection intent and
 * hands the result here. The service stops as soon as one frame is written.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null || data == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        runCatching {
            val mediaProjection = manager.getMediaProjection(resultCode, data)
                ?: return@runCatching
            projection = mediaProjection
            mediaProjection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        release()
                    }
                },
                Handler(mainLooper)
            )

            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = imageReader

            val worker = HandlerThread("screen-capture").also { it.start() }
            thread = worker

            imageReader.setOnImageAvailableListener({ available ->
                val image = available.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val bitmap = image.toBitmap()
                    val file = File(cacheDir, "capture_${System.currentTimeMillis()}.png")
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 92, out)
                    }
                    bitmap.recycle()
                    sendBroadcast(
                        Intent(ACTION_CAPTURED)
                            .setPackage(packageName)
                            .putExtra(EXTRA_FILE, file.absolutePath)
                    )
                } finally {
                    image.close()
                    release()
                    stopSelf()
                }
            }, Handler(worker.looper))

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "heyandroid-capture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                Handler(worker.looper)
            )
        }.onFailure {
            release()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun android.media.Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        if (cropped !== bitmap) bitmap.recycle()
        return cropped
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Screen capture",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screenshot)
            .setContentTitle("Capturing the screen")
            .setContentText("One frame for HeyAndroid")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun release() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { reader?.close() }
        reader = null
        runCatching { projection?.stop() }
        projection = null
        thread?.quitSafely()
        thread = null
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CAPTURED = "com.example.heyandroid.action.SCREEN_CAPTURED"
        const val EXTRA_FILE = "extra_file"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 7412
        private const val FOREGROUND_SERVICE_TYPE =
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    }
}
