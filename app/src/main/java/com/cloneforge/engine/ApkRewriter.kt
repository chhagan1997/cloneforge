package com.cloneforge.engine

import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Re-packs an APK with a replaced AndroidManifest.xml, drops the old signature files and
 * keeps stored entries aligned (4 bytes, 16 KB for .so) so Android 11+ accepts the result.
 * Pure JVM.
 */
object ApkRewriter {

    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    private fun isSignature(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val n = name.uppercase()
        return n == "META-INF/MANIFEST.MF" || n.endsWith(".SF") || n.endsWith(".RSA") ||
            n.endsWith(".DSA") || n.endsWith(".EC") || n.startsWith("META-INF/SIG-")
    }

    fun rewrite(src: File, dst: File, newManifest: ByteArray) {
        ZipFile(src).use { zf ->
            val cnt = Counting(dst.outputStream().buffered(1 shl 16))
            ZipOutputStream(cnt).use { zos ->
                zos.setLevel(6)
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val se = entries.nextElement()
                    val name = se.name
                    if (isSignature(name)) continue
                    val isManifest = name == "AndroidManifest.xml"

                    val stored = if (isManifest) false else se.method == ZipEntry.STORED
                    val ze = ZipEntry(name)
                    ze.time = if (se.time > 0) se.time else 315532800000L
                    if (stored) {
                        ze.method = ZipEntry.STORED
                        ze.size = se.size; ze.compressedSize = se.size; ze.crc = se.crc
                        val align = if (name.endsWith(".so")) 16384 else 4
                        val nameLen = name.toByteArray(Charsets.UTF_8).size
                        val pad = ((align - ((cnt.count + 30 + nameLen) % align)) % align).toInt()
                        if (pad > 0) ze.extra = ByteArray(pad)
                    } else {
                        ze.method = ZipEntry.DEFLATED
                    }
                    zos.putNextEntry(ze)
                    if (isManifest) zos.write(newManifest)
                    else if (!se.isDirectory) zf.getInputStream(se).use { it.copyTo(zos, 1 shl 16) }
                    zos.closeEntry()
                }
            }
        }
    }

    /** Convenience: patch [src]'s manifest and write the unsigned result to [dst]. */
    fun patch(src: File, dst: File, patch: ManifestPatch) {
        val bytes = ZipFile(src).use { zf ->
            zf.getInputStream(zf.getEntry("AndroidManifest.xml")).use { it.readBytes() }
        }
        val doc = Axml.parse(bytes)
        ManifestPatcher(doc).apply(patch)
        rewrite(src, dst, doc.toBytes())
    }
}
