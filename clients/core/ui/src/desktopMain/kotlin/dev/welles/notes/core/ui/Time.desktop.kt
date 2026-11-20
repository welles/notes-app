package dev.welles.notes.core.ui

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.toJavaInstant

internal actual fun kotlin.time.Instant.toLocalDateTimeString(): String =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(toJavaInstant())
