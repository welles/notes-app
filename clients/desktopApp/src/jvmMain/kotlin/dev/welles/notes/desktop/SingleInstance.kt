package dev.welles.notes.desktop

import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.Base64
import kotlin.concurrent.thread

/**
 * Single instance via a Unix socket ($XDG_RUNTIME_DIR/notes.sock): a second start forwards its arguments
 * to the running instance and exits.
 */
object SingleInstance {
    private val socketFile: File = File(
        System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() } ?: System.getProperty("java.io.tmpdir"),
        System.getenv("NOTES_SOCKET_NAME") ?: "notes.sock",
    )

    /** Sends the arguments to a running instance; true if one received them. */
    fun forward(args: List<String>): Boolean = runCatching {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
            ch.connect(UnixDomainSocketAddress.of(socketFile.toPath()))
            val line = args.joinToString(" ") { Base64.getEncoder().encodeToString(it.toByteArray()) } + "\n"
            ch.write(ByteBuffer.wrap(line.toByteArray()))
        }
        true
    }.getOrDefault(false)

    /** Becomes the running instance; [onArgs] is called for every forwarded command line. */
    fun listen(onArgs: (List<String>) -> Unit) {
        socketFile.delete() // stale socket of a crashed instance (forward() failed, so nobody listens)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socketFile.toPath()))
        socketFile.setReadable(false, false); socketFile.setReadable(true, true)
        socketFile.setWritable(false, false); socketFile.setWritable(true, true)
        Runtime.getRuntime().addShutdownHook(Thread { socketFile.delete() })
        thread(isDaemon = true, name = "notes-single-instance") {
            while (true) {
                val ch = runCatching { server.accept() }.getOrNull() ?: continue
                runCatching {
                    ch.use {
                        val buf = ByteBuffer.allocate(64 * 1024)
                        while (it.read(buf) > 0) Unit
                        val text = String(buf.array(), 0, buf.position()).trim()
                        val args = if (text.isEmpty()) emptyList() else text.split(" ").map { a -> String(Base64.getDecoder().decode(a)) }
                        onArgs(args)
                    }
                }
            }
        }
    }
}
