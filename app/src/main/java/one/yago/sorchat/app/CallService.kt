package one.yago.sorchat.app

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service for the duration of a call. Its notification is the incoming-call
 * screen (full-screen over the lock screen) while ringing, then the ongoing-call notification.
 * Android only keeps the process, and microphone access, alive in the background while it runs.
 */
class CallService : Service() {
    enum class Mode { INCOMING, ONGOING }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val calls = ChatRepository.get(this).calls
        when (intent?.action) {
            ACTION_DECLINE -> {
                calls.decline()
                return START_NOT_STICKY
            }
            ACTION_HANG_UP -> {
                calls.hangUp()
                return START_NOT_STICKY
            }
        }
        val mode = intent?.getStringExtra(EXTRA_MODE)?.let(Mode::valueOf) ?: Mode.ONGOING
        val telecom = intent?.getBooleanExtra(EXTRA_TELECOM, false) ?: false
        val peer = Person.Builder().setName(intent?.getStringExtra(EXTRA_PEER_NAME) ?: "Call").setImportant(true).build()

        val notification = when (mode) {
            Mode.INCOMING -> NotificationCompat.Builder(this, Notifications.CHANNEL_INCOMING_CALLS)
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(peer, serviceIntent(ACTION_DECLINE), activityIntent(MainActivity.ACTION_ANSWER)))
                .setFullScreenIntent(activityIntent(null), true)
            Mode.ONGOING -> NotificationCompat.Builder(this, Notifications.CHANNEL_CALLS)
                .setStyle(NotificationCompat.CallStyle.forOngoingCall(peer, serviceIntent(ACTION_HANG_UP)))
        }
            .setSmallIcon(R.drawable.ic_call)
            .setContentIntent(activityIntent(null))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .build()

        // phoneCall is allowed from the background for calls registered with Telecom; the
        // microphone type isn't, so it's only added once the user has answered (from the UI).
        var types = if (telecom) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0
        if (mode == Mode.ONGOING) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
        } catch (e: Exception) {
            // Without Telecom, a call that comes in while the app is in the background can't run a
            // foreground service; it then only rings while the app is open.
            Log.w(TAG, "Couldn't start call foreground service", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun activityIntent(action: String?): PendingIntent = PendingIntent.getActivity(
        this, action.hashCode(),
        Intent(this, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, CallService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "CallService"
        private const val ACTION_DECLINE = "one.yago.sorchat.DECLINE"
        private const val ACTION_HANG_UP = "one.yago.sorchat.HANG_UP"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_TELECOM = "telecom"
        private const val EXTRA_PEER_NAME = "peer_name"
        private const val NOTIFICATION_ID = 1

        /** Starts the service, or switches its notification to [mode]. */
        fun update(context: Context, peerName: String, mode: Mode, telecom: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallService::class.java)
                    .putExtra(EXTRA_PEER_NAME, peerName)
                    .putExtra(EXTRA_MODE, mode.name)
                    .putExtra(EXTRA_TELECOM, telecom),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
