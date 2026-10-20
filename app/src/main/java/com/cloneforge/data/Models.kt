package com.cloneforge.data

import android.graphics.drawable.Drawable

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val apkPaths: List<String>,      // base first, then splits
    val permissions: List<String>,
    val fromFile: Boolean = false
)

data class CloneOptions(
    val name: String,
    val packageName: String,
    val removedPermissions: Set<String> = emptySet(),
    val disableBackup: Boolean = true,
    val excludeRecents: Boolean = false,
    val noAutoStart: Boolean = false,
    val noWidgets: Boolean = false
)

private val PKG_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
fun isValidPackage(p: String) = PKG_REGEX.matches(p)
