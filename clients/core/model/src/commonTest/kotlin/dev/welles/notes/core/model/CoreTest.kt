package dev.welles.notes.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class CoreTest {
    @Test
    fun ids_are_uuid_v7_and_time_ordered() {
        val a = Ids.newId()
        val b = Ids.newId()
        assertEquals('7', a[14])
        assertTrue(a.length == 36)
        assertTrue(a.substring(0, 13) <= b.substring(0, 13))
    }

    @Test
    fun sync_time_has_millisecond_precision() {
        assertEquals("2026-11-20T10:00:00.000Z", SyncTime.format(Instant.parse("2026-11-20T10:00:00Z")))
        assertEquals("2026-11-20T10:00:00.120Z", SyncTime.format(Instant.parse("2026-11-20T10:00:00.12Z")))
        assertEquals("2026-11-20T10:00:00.123Z", SyncTime.format(Instant.parse("2026-11-20T10:00:00.123456Z")))
    }
}
