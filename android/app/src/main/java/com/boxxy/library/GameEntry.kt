package com.boxxy.library

import android.net.Uri

data class GameEntry(
    val uri: Uri,
    val displayName: String,
    val coverUri: Uri?,
)
