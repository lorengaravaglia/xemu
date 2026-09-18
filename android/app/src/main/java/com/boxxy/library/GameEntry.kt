package com.boxxy.library

import android.net.Uri

data class GameEntry(
    val uri: Uri,
    /** Name shown in the grid — from the file or folder, so the user controls it. */
    val displayName: String,
    val coverUri: Uri?,
    /**
     * Title as recorded in the disc's XBE certificate. Used for art lookups,
     * where it matches far better than a filename, but deliberately not shown:
     * it is often an internal short form ("MGS2 SUBSTANCE").
     */
    val discTitle: String? = null,
    /** Title ID from the disc — a stable artwork cache key. */
    val titleId: Int? = null,
    /**
     * True when [coverUri] is the disc's own title image. Those are square and
     * only 128x128, so the card letterboxes them instead of cropping to the
     * portrait aspect a real cover assumes.
     */
    val coverIsDiscArt: Boolean = false,
)
