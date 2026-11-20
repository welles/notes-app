package dev.welles.notes.feature.stickies

/**
 * "Auf den Desktop legen": only available on the desktop client (registered there via Koin).
 * The menu item is hidden when no implementation is present.
 */
interface StickyDesktopPlacement {
    fun place(stickyId: String)
}
