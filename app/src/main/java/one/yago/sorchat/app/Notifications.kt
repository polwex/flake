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
    }

    @SuppressLint("MissingPermission") // checked by canNotify()
    fun showMessage(message: ChatMessage, sender: Contact) {
        if (!canNotify()) return
        val id = notificationId(sender.id)
        val existing = manager.activeNotifications.firstOrNull { it.id == id }?.notification
        val style = existing?.let(NotificationCompat.MessagingStyle::extractMessagingStyleFromNotification)
            ?: NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
        style.addMessage(message.body, message.sentAt, Person.Builder().setKey(sender.id).setName(sender.name).build())

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

    fun cancel(contactId: String) = manager.cancel(notificationId(contactId))

    private fun canNotify(): Boolean =
        manager.areNotificationsEnabled() && (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    private fun notificationId(contactId: String) = contactId.hashCode()

    private companion object {
        const val CHANNEL_MESSAGES = "messages"
    }
}
