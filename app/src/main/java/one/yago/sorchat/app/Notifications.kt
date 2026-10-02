package one.yago.sorchat.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat

/** One notification per conversation, accumulating messages until the chat is opened. */
class Notifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_MESSAGES, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Messages")
                .build()
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_CALLS, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Ongoing calls")
                .build()
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_INCOMING_CALLS, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Incoming calls")
                // The app plays the ringtone itself, looping until answered.
                .setSound(null, null)
                .build()
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_MISSED_CALLS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Missed calls")
                .build()
        )
    }

    @SuppressLint("MissingPermission") // checked by canNotify()
    fun showMessage(message: ChatMessage, sender: Contact) {
        if (!canNotify()) return
        val id = notificationId(sender.id)
        val existing = manager.activeNotifications.firstOrNull { it.id == id }?.notification
        val style = existing?.let(NotificationCompat.MessagingStyle::extractMessagingStyleFromNotification)
            ?: NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
        style.addMessage(message.preview(), message.sentAt, Person.Builder().setKey(sender.id).setName(sender.name).build())

        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CHAT, sender.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    @SuppressLint("MissingPermission") // checked by canNotify()
    fun showMissedCall(caller: Contact) {
        if (!canNotify()) return
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CHAT, caller.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = notificationId("missed:" + caller.id)
        val notification = NotificationCompat.Builder(context, CHANNEL_MISSED_CALLS)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Missed call")
            .setContentText(caller.name)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    fun cancel(contactId: String) = manager.cancel(notificationId(contactId))

    private fun canNotify(): Boolean =
        manager.areNotificationsEnabled() && (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    private fun notificationId(contactId: String) = contactId.hashCode()

    companion object {
        private const val CHANNEL_MESSAGES = "messages"
        private const val CHANNEL_MISSED_CALLS = "missed_calls"
        const val CHANNEL_CALLS = "calls"
        const val CHANNEL_INCOMING_CALLS = "incoming_calls"
    }
}
