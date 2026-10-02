package one.yago.sorchat.app.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun Long.toLocal() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())

fun dayOf(millis: Long): LocalDate = millis.toLocal().toLocalDate()

/** Time of day in the user's locale, e.g. "14:05" or "2:05 PM". */
fun formatMessageTime(millis: Long): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(millis.toLocal())

/** For the chat list: time today, "Yesterday", a weekday within the week, else a date. */
fun formatListTime(millis: Long): String {
    val day = dayOf(millis)
    val today = LocalDate.now()
    return when {
        day == today -> formatMessageTime(millis)
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> DateTimeFormatter.ofPattern("EEE").format(day)
        else -> DateTimeFormatter.ofPattern("d MMM").format(day)
    }
}

/** Separator between days in a chat. */
fun formatDayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.year == today.year -> DateTimeFormatter.ofPattern("EEEE, d MMMM").format(day)
        else -> DateTimeFormatter.ofPattern("d MMMM yyyy").format(day)
    }
}
