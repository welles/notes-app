package dev.welles.notes.desktop

import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.platform.unix.X11
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.Window

/**
 * EWMH window hints AWT does not offer (Below, Skip-Taskbar, Sticky, role/class), set directly via Xlib (JNA).
 * Works on X11 (Cinnamon) and for XWayland windows (KWin honours most hints; the rest via KWin window rules).
 * Without X11 (e.g. no DISPLAY) every call is a no-op.
 */
object X11Hints {
    enum class Layer { Normal, Above, Below }

    private val x11: X11? = runCatching { X11.INSTANCE }.getOrNull()
    private val display: X11.Display? by lazy { if (System.getenv("DISPLAY").isNullOrEmpty()) null else x11?.XOpenDisplay(null) }

    val available: Boolean get() = display != null

    private fun atom(name: String): X11.Atom = x11!!.XInternAtom(display, name, false)

    fun windowId(window: Window): Long? = runCatching { Native.getWindowID(window) }.getOrNull()?.takeIf { it != 0L }

    /**
     * Sets hints before the window is mapped (as _NET_WM_STATE property, WM_WINDOW_ROLE, WM_CLASS)
     * – window managers read them when they start managing the window.
     */
    fun prepare(window: Window, layer: Layer, wmClass: String, role: String) {
        val d = display ?: return
        val id = windowId(window) ?: return
        val w = X11.Window(id)
        val states = buildList {
            add(atom("_NET_WM_STATE_SKIP_TASKBAR"))
            add(atom("_NET_WM_STATE_SKIP_PAGER"))
            add(atom("_NET_WM_STATE_STICKY"))
            when (layer) {
                Layer.Above -> add(atom("_NET_WM_STATE_ABOVE"))
                Layer.Below -> add(atom("_NET_WM_STATE_BELOW"))
                Layer.Normal -> Unit
            }
        }
        setAtoms(w, "_NET_WM_STATE", states)
        setCardinal(w, "_NET_WM_DESKTOP", 0xFFFFFFFFL) // all workspaces
        setString(w, "WM_WINDOW_ROLE", role)
        setString(w, "WM_CLASS", "$wmClass\u0000$wmClass", type = "STRING")
        x11!!.XFlush(d)
    }

    /** Changes state of a mapped window via client message to the root window (EWMH). */
    fun setLayer(window: Window, layer: Layer) {
        val d = display ?: return
        val id = windowId(window) ?: return
        send(d, id, if (layer == Layer.Above) 1 else 0, "_NET_WM_STATE_ABOVE")
        send(d, id, if (layer == Layer.Below) 1 else 0, "_NET_WM_STATE_BELOW")
        x11!!.XFlush(d)
    }

    /** Re-asserts skip-taskbar/sticky after mapping (some WMs reset state on map). */
    fun reassert(window: Window) {
        val d = display ?: return
        val id = windowId(window) ?: return
        send(d, id, 1, "_NET_WM_STATE_SKIP_TASKBAR", "_NET_WM_STATE_SKIP_PAGER")
        send(d, id, 1, "_NET_WM_STATE_STICKY")
        sendDesktop(d, id, 0xFFFFFFFFL)
        x11!!.XFlush(d)
    }

    /** Reads back the _NET_WM_STATE atom names (spike diagnostics). */
    fun currentState(window: Window): List<String> {
        val d = display ?: return emptyList()
        val id = windowId(window) ?: return emptyList()
        val typeRet = X11.AtomByReference()
        val formatRet = IntByReference()
        val nItems = NativeLongByReference()
        val after = NativeLongByReference()
        val data = PointerByReference()
        val ok = x11!!.XGetWindowProperty(d, X11.Window(id), atom("_NET_WM_STATE"), NativeLong(0), NativeLong(64), false,
            X11.XA_ATOM, typeRet, formatRet, nItems, after, data)
        if (ok != 0 || data.value == null) return emptyList()
        val n = nItems.value.toInt()
        val result = (0 until n).map { i -> x11.XGetAtomName(d, X11.Atom(data.value.getNativeLong(i.toLong() * NativeLong.SIZE).toLong())) }
        x11.XFree(data.value)
        return result
    }

    private fun send(d: X11.Display, id: Long, action: Int, first: String, second: String? = null) {
        val event = X11.XEvent()
        event.type = X11.ClientMessage
        event.setType(X11.XClientMessageEvent::class.java)
        event.xclient.type = X11.ClientMessage
        event.xclient.serial = NativeLong(0)
        event.xclient.send_event = 1
        event.xclient.message_type = atom("_NET_WM_STATE")
        event.xclient.window = X11.Window(id)
        event.xclient.format = 32
        event.xclient.data.setType(Array<NativeLong>::class.java)
        event.xclient.data.l[0] = NativeLong(action.toLong())
        event.xclient.data.l[1] = NativeLong(atom(first).toLong())
        event.xclient.data.l[2] = NativeLong(second?.let { atom(it).toLong() } ?: 0)
        event.xclient.data.l[3] = NativeLong(1)
        x11!!.XSendEvent(d, x11.XDefaultRootWindow(d), 0, NativeLong(X11.SubstructureRedirectMask.toLong() or X11.SubstructureNotifyMask.toLong()), event)
    }

    private fun sendDesktop(d: X11.Display, id: Long, desktop: Long) {
        val event = X11.XEvent()
        event.type = X11.ClientMessage
        event.setType(X11.XClientMessageEvent::class.java)
        event.xclient.type = X11.ClientMessage
        event.xclient.send_event = 1
        event.xclient.message_type = atom("_NET_WM_DESKTOP")
        event.xclient.window = X11.Window(id)
        event.xclient.format = 32
        event.xclient.data.setType(Array<NativeLong>::class.java)
        event.xclient.data.l[0] = NativeLong(desktop)
        event.xclient.data.l[1] = NativeLong(1)
        x11!!.XSendEvent(d, x11.XDefaultRootWindow(d), 0, NativeLong(X11.SubstructureRedirectMask.toLong() or X11.SubstructureNotifyMask.toLong()), event)
    }

    private fun setAtoms(w: X11.Window, property: String, atoms: List<X11.Atom>) {
        val mem = com.sun.jna.Memory(atoms.size.toLong() * NativeLong.SIZE)
        atoms.forEachIndexed { i, a -> mem.setNativeLong(i.toLong() * NativeLong.SIZE, NativeLong(a.toLong())) }
        x11!!.XChangeProperty(display, w, atom(property), X11.XA_ATOM, 32, X11.PropModeReplace, mem, atoms.size)
    }

    private fun setCardinal(w: X11.Window, property: String, value: Long) {
        val mem = com.sun.jna.Memory(NativeLong.SIZE.toLong())
        mem.setNativeLong(0, NativeLong(value))
        x11!!.XChangeProperty(display, w, atom(property), X11.XA_CARDINAL, 32, X11.PropModeReplace, mem, 1)
    }

    private fun setString(w: X11.Window, property: String, value: String, type: String = "STRING") {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val mem = com.sun.jna.Memory(bytes.size.toLong().coerceAtLeast(1))
        mem.write(0, bytes, 0, bytes.size)
        x11!!.XChangeProperty(display, w, atom(property), atom(type), 8, X11.PropModeReplace, mem, bytes.size)
    }

    @Suppress("unused")
    private fun unused(p: Pointer?) = p
}
