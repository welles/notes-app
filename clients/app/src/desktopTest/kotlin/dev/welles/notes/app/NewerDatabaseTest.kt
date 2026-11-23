package dev.welles.notes.app

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.Dispatchers
import org.koin.dsl.koinApplication

class NewerDatabaseTest {
    /** As at startup: the exception comes out of the Koin definition (possibly wrapped) and must still be recognised. */
    @Test
    fun newerDatabaseIsFoundThroughKoin() {
        val file = File.createTempFile("notes-newer", ".db").apply { delete(); deleteOnExit() }
        BundledSQLiteDriver().open(file.path).apply { execSQL("PRAGMA user_version = ${NotesDatabase.VERSION + 1}"); close() }

        val koin = koinApplication {
            modules(databaseModule(builder = {
                checkDatabaseVersion(file.path)
                Room.databaseBuilder<NotesDatabase>(file.path)
            }, io = Dispatchers.IO))
        }.koin
        val error = runCatching { koin.get<NotesDatabase>() }.exceptionOrNull()

        assertNotNull(error)
        assertEquals(NotesDatabase.VERSION + 1, error.newerDatabase()?.found)
    }
}
