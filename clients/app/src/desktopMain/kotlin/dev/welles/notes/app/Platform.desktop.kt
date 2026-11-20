package dev.welles.notes.app

import androidx.room.Room
import java.io.File
import kotlinx.coroutines.Dispatchers

/** XDG base directories: data in $XDG_DATA_HOME/notes, settings in $XDG_CONFIG_HOME/notes. */
object DesktopDirs {
    private val home = System.getProperty("user.home")
    val data: File = File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "notes").apply { mkdirs() }
    val config: File = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "notes").apply { mkdirs() }
}

fun desktopDatabaseModule(dataDir: File = DesktopDirs.data) = databaseModule(
    builder = {
        val file = File(dataDir, NotesDatabase.FILE_NAME)
        checkDatabaseVersion(file.absolutePath)
        Room.databaseBuilder<NotesDatabase>(file.absolutePath)
    },
    io = Dispatchers.IO,
)
