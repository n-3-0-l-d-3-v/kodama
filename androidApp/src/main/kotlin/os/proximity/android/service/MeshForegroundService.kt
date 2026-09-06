package os.proximity.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import os.proximity.android.MainActivity
import os.proximity.android.R

/**
 * Keeps the mesh running while the user is in another app.
 *
 * This service is **opt-in**. Android would happily let us start it on
 * launch and keep the radios going forever, but an app whose pitch is
 * "default deny, and you can see everything it does" should not quietly
 * hold the Bluetooth radio open in the background. The user turns it on,
 * and the notification says plainly what it is doing.
 *
 * The service holds no mesh state of its own. The `MeshManager` lives on
 * the application-scoped graph; this exists purely to tell Android the
 * process is doing something the user asked for and should not be killed.
 */
class MeshForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startInForeground()

        // START_STICKY would have Android restart us after a kill, which
        // would resume the radios without the user asking. If the system
        // needs the memory, staying stopped is the more honest behaviour.
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MeshForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kodama is running")
            .setContentText("Looking for nearby devices. Nothing is shared without your approval.")
            .setSmallIcon(R.drawable.ic_mesh_notification)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Mesh activity",
                // Low: this is a persistent status, not something to
                // interrupt the user with.
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while Kodama is looking for nearby devices."
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "proximity_mesh"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "os.proximity.android.STOP_MESH"

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshForegroundService::class.java))
        }
    }
}
