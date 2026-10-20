package com.cloneforge.engine

import android.content.Context
import com.cloneforge.data.AppEntry
import com.cloneforge.data.CloneOptions
import java.io.File

/** Orchestrates: patch manifest -> repack -> sign. Returns the signed APK(s), base first. */
class CloneEngine(private val ctx: Context) {

    fun build(src: AppEntry, o: CloneOptions, onStep: (String) -> Unit): List<File> {
        val work = File(ctx.cacheDir, "clones/${o.packageName}").apply { deleteRecursively(); mkdirs() }
        val signed = ArrayList<File>()
        src.apkPaths.forEachIndexed { i, path ->
            val base = i == 0
            onStep(if (base) "Patching manifest…" else "Patching split $i…")
            val unsigned = File(work, "unsigned_$i.apk")
            val patch = if (base) ManifestPatch(
                newPackage = o.packageName,
                label = o.name.ifBlank { null },
                removePermissions = o.removedPermissions,
                allowBackup = if (o.disableBackup) false else null,
                excludeFromRecents = o.excludeRecents,
                removeBootReceivers = o.noAutoStart,
                removeWidgets = o.noWidgets
            ) else ManifestPatch(newPackage = o.packageName)
            ApkRewriter.patch(File(path), unsigned, patch)

            onStep(if (base) "Signing…" else "Signing split $i…")
            val out = File(work, "signed_$i.apk")
            CloneSigner.sign(unsigned, out)
            unsigned.delete()
            signed.add(out)
        }
        return signed
    }
}
