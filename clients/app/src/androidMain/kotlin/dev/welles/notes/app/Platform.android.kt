package dev.welles.notes.app

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import org.koin.dsl.module

fun androidDatabaseModule(context: Context) = databaseModule(
    builder = {
        val file = context.getDatabasePath(NotesDatabase.FILE_NAME)
        checkDatabaseVersion(file.absolutePath)
        Room.databaseBuilder<NotesDatabase>(context.applicationContext, file.absolutePath)
    },
    io = Dispatchers.IO,
)

fun androidPlatformModule(context: Context) = module { single<Context> { context.applicationContext } }
