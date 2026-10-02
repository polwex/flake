package one.yago.sorchat.app

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service for the duration of a call: Android only keeps microphone access and the
 * process alive in the background while one runs. Shows the ongoing-call notification.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANG_UP) {
            ChatRepository.get(this).calls.hangUp()
            return START_NOT_STICKY
        }
        val peerName = intent?.getStringExtra(EXTRA_PEER_NAME) ?: "Call"
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val hangUp = PendingIntent.getService(
            this, 1,
            Intent(this, CallService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(peerName)
            .setContentText("Ongoing call")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .addAction(R.drawable.ic_call_end, "Hang up", hangUp)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_NOT_STICKY
    }

    companion object {
        private const val ACTION_HANG_UP = "one.yago.sorchat.HANG_UP"
        private const val EXTRA_PEER_NAME = "peer_name"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context, peerName: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallService::class.java).putExtra(EXTRA_PEER_NAME, peerName),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
