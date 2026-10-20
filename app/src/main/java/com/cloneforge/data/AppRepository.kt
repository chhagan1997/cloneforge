package com.cloneforge.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File

class AppRepository(private val ctx: Context) {
    private val pm: PackageManager = ctx.packageManager

    @Suppress("DEPRECATION")
    fun loadInstalled(): List<AppEntry> =
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .asSequence()
            .filter { it.packageName != ctx.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
            .mapNotNull { toEntry(it, fromFile = false) }
            .sortedBy { it.label.lowercase() }
            .toList()

    @Suppress("DEPRECATION")
    fun fromApk(file: File): AppEntry? {
        val info = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_PERMISSIONS) ?: return null
        info.applicationInfo?.apply { sourceDir = file.absolutePath; publicSourceDir = file.absolutePath }
        return toEntry(info, fromFile = true)
    }

    fun isInstalled(pkg: String): Boolean = try {
        @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** First free "<pkg>.cloneN" name. */
    fun suggestPackage(original: String): String {
        for (n in 1..999) { val p = "$original.clone$n"; if (!isInstalled(p)) return p }
        return "$original.clone${System.currentTimeMillis() % 100000}"
    }

    private fun toEntry(info: PackageInfo, fromFile: Boolean): AppEntry? {
        val ai = info.applicationInfo ?: return null
        val paths = buildList {
            add(ai.sourceDir)
            ai.splitSourceDirs?.let { addAll(it) }
        }
        return AppEntry(
            packageName = info.packageName,
            label = ai.loadLabel(pm).toString(),
            icon = runCatching { ai.loadIcon(pm) }.getOrNull(),
            apkPaths = paths,
            permissions = info.requestedPermissions?.toList() ?: emptyList(),
            fromFile = fromFile
        )
    }
}
