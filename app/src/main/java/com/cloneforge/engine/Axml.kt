package com.cloneforge.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal binary AndroidManifest.xml (AXML) reader/writer. Pure JVM, no Android imports.
 * The document is held as a flat list of events; the string pool and resource map are
 * rebuilt on write, so strings can be freely added, changed and removed.
 */
const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

const val TYPE_REFERENCE = 0x01
const val TYPE_STRING = 0x03
const val TYPE_INT_DEC = 0x10
const val TYPE_INT_HEX = 0x11
const val TYPE_BOOLEAN = 0x12

class Attr(
    var ns: String?,
    var name: String,
    var resId: Int,
    var type: Int,
    var data: Int,
    var str: String?,   // value when type == TYPE_STRING
    var raw: String?    // original raw text for non-string types (optional)
)

sealed class Ev { var line: Int = 1 }
class StartNs(val prefix: String?, val uri: String?) : Ev()
class EndNs(val prefix: String?, val uri: String?) : Ev()
class StartEl(var ns: String?, var name: String, val attrs: MutableList<Attr>) : Ev()
class EndEl(var ns: String?, var name: String) : Ev()

class Axml(val events: MutableList<Ev>) {

    companion object {
        private const val CH_XML = 0x0003
        private const val CH_POOL = 0x0001
        private const val CH_RESMAP = 0x0180
        private const val CH_START_NS = 0x0100
        private const val CH_END_NS = 0x0101
        private const val CH_START_EL = 0x0102
        private const val CH_END_EL = 0x0103

        fun parse(bytes: ByteArray): Axml {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require((b.getShort(0).toInt() and 0xffff) == CH_XML) { "Not a binary XML file" }
            val total = minOf(b.getInt(4), bytes.size)
            var pos = b.getShort(2).toInt() and 0xffff
            var pool: List<String> = emptyList()
            var resMap = IntArray(0)
            val evs = ArrayList<Ev>()

            fun s(i: Int): String? = if (i == -1) null else pool[i]

            while (pos + 8 <= total) {
                val type = b.getShort(pos).toInt() and 0xffff
                val hs = b.getShort(pos + 2).toInt() and 0xffff
                val size = b.getInt(pos + 4)
                when (type) {
                    CH_POOL -> pool = readPool(b, pos)
                    CH_RESMAP -> {
                        val n = (size - hs) / 4
                        resMap = IntArray(n) { b.getInt(pos + hs + it * 4) }
                    }
                    CH_START_NS, CH_END_NS -> {
                        val e = if (type == CH_START_NS) StartNs(s(b.getInt(pos + 16)), s(b.getInt(pos + 20)))
                        else EndNs(s(b.getInt(pos + 16)), s(b.getInt(pos + 20)))
                        e.line = b.getInt(pos + 8); evs.add(e)
                    }
                    CH_START_EL -> {
                        val ns = s(b.getInt(pos + 16))
                        val name = s(b.getInt(pos + 20))!!
                        val attrStart = b.getShort(pos + 24).toInt() and 0xffff
                        val attrSize = b.getShort(pos + 26).toInt() and 0xffff
                        val attrCount = b.getShort(pos + 28).toInt() and 0xffff
                        val attrs = ArrayList<Attr>(attrCount)
                        for (i in 0 until attrCount) {
                            val a = pos + 16 + attrStart + i * attrSize
                            val ans = s(b.getInt(a))
                            val nameIdx = b.getInt(a + 4)
                            val rawIdx = b.getInt(a + 8)
                            val dt = b.get(a + 15).toInt() and 0xff
                            val data = b.getInt(a + 16)
                            val resId = if (nameIdx in resMap.indices) resMap[nameIdx] else 0
                            attrs.add(
                                if (dt == TYPE_STRING) Attr(ans, pool[nameIdx], resId, dt, 0, pool[data], null)
                                else Attr(ans, pool[nameIdx], resId, dt, data, null, s(rawIdx))
                            )
                        }
                        val e = StartEl(ns, name, attrs); e.line = b.getInt(pos + 8); evs.add(e)
                    }
                    CH_END_EL -> {
                        val e = EndEl(s(b.getInt(pos + 16)), s(b.getInt(pos + 20))!!)
                        e.line = b.getInt(pos + 8); evs.add(e)
                    }
                    else -> throw IllegalArgumentException("Unsupported chunk 0x%x in manifest".format(type))
                }
                if (size <= 0) break
                pos += size
            }
            return Axml(evs)
        }

        private fun readPool(b: ByteBuffer, pos: Int): List<String> {
            val hs = b.getShort(pos + 2).toInt() and 0xffff
            val count = b.getInt(pos + 8)
            val flags = b.getInt(pos + 16)
            val strStart = b.getInt(pos + 20)
            val utf8 = (flags and 0x100) != 0
            val out = ArrayList<String>(count)
            for (i in 0 until count) {
                var p = pos + strStart + b.getInt(pos + hs + i * 4)
                if (utf8) {
                    var u = b.get(p++).toInt() and 0xff
                    if (u and 0x80 != 0) { p++ }                      // char length (ignored), 2-byte form
                    var l = b.get(p++).toInt() and 0xff                // byte length
                    if (l and 0x80 != 0) { l = ((l and 0x7f) shl 8) or (b.get(p++).toInt() and 0xff) }
                    val arr = ByteArray(l); for (k in 0 until l) arr[k] = b.get(p + k)
                    out.add(String(arr, Charsets.UTF_8))
                } else {
                    var l = b.getShort(p).toInt() and 0xffff; p += 2
                    if (l and 0x8000 != 0) { l = ((l and 0x7fff) shl 16) or (b.getShort(p).toInt() and 0xffff); p += 2 }
                    val sb = StringBuilder(l)
                    for (k in 0 until l) sb.append((b.getShort(p + k * 2).toInt() and 0xffff).toChar())
                    out.add(sb.toString())
                }
            }
            return out
        }
    }

    // ---------------------------------------------------------------- writer

    fun toBytes(): ByteArray {
        // 1. string pool: attribute names that carry a resource id come first (sorted by id),
        //    because the resource-map chunk is positional.
        val mapped = LinkedHashMap<String, Int>()
        for (e in events) if (e is StartEl) for (a in e.attrs) if (a.resId != 0 && a.name !in mapped) mapped[a.name] = a.resId
        val mappedSorted = mapped.entries.sortedWith(compareBy({ it.value }, { it.key }))
        val strings = LinkedHashSet<String>()
        for (m in mappedSorted) strings.add(m.key)
        fun add(s: String?) { if (s != null) strings.add(s) }
        for (e in events) when (e) {
            is StartNs -> { add(e.prefix); add(e.uri) }
            is EndNs -> { add(e.prefix); add(e.uri) }
            is StartEl -> { add(e.ns); add(e.name); for (a in e.attrs) { add(a.ns); add(a.name); add(a.str); add(a.raw) } }
            is EndEl -> { add(e.ns); add(e.name) }
        }
        val list = strings.toList()
        val index = HashMap<String, Int>(list.size * 2).also { m -> list.forEachIndexed { i, s -> m[s] = i } }
        fun idx(s: String?): Int = if (s == null) -1 else index[s]!!

        val body = ByteArrayOutputStream()
        fun w16(v: Int) { body.write(v and 0xff); body.write((v shr 8) and 0xff) }
        fun w32(v: Int) { w16(v and 0xffff); w16((v ushr 16) and 0xffff) }

        val poolBytes = buildPool(list)
        val resBytes = ByteArrayOutputStream()
        if (mappedSorted.isNotEmpty()) {
            val rb = ByteBuffer.allocate(8 + 4 * mappedSorted.size).order(ByteOrder.LITTLE_ENDIAN)
            rb.putShort(CH_RESMAP.toShort()); rb.putShort(8); rb.putInt(8 + 4 * mappedSorted.size)
            for (m in mappedSorted) rb.putInt(m.value)
            resBytes.write(rb.array())
        }

        for (e in events) when (e) {
            is StartNs, is EndNs -> {
                val (t, p, u) = if (e is StartNs) Triple(CH_START_NS, e.prefix, e.uri)
                else { e as EndNs; Triple(CH_END_NS, e.prefix, e.uri) }
                w16(t); w16(16); w32(24); w32(e.line); w32(-1); w32(idx(p)); w32(idx(u))
            }
            is StartEl -> {
                w16(CH_START_EL); w16(16); w32(36 + 20 * e.attrs.size); w32(e.line); w32(-1)
                w32(idx(e.ns)); w32(idx(e.name)); w16(20); w16(20); w16(e.attrs.size); w16(0); w16(0); w16(0)
                for (a in e.attrs) {
                    w32(idx(a.ns)); w32(idx(a.name))
                    if (a.type == TYPE_STRING) { val i = idx(a.str); w32(i); w16(8); body.write(0); body.write(TYPE_STRING); w32(i) }
                    else { w32(idx(a.raw)); w16(8); body.write(0); body.write(a.type); w32(a.data) }
                }
            }
            is EndEl -> { w16(CH_END_EL); w16(16); w32(24); w32(e.line); w32(-1); w32(idx(e.ns)); w32(idx(e.name)) }
        }

        val total = 8 + poolBytes.size + resBytes.size() + body.size()
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(CH_XML.toShort()); out.putShort(8); out.putInt(total)
        out.put(poolBytes); out.put(resBytes.toByteArray()); out.put(body.toByteArray())
        return out.array()
    }

    private fun buildPool(list: List<String>): ByteArray {
        val data = ByteArrayOutputStream()
        val offs = IntArray(list.size)
        fun w16(v: Int) { data.write(v and 0xff); data.write((v shr 8) and 0xff) }
        list.forEachIndexed { i, s ->
            offs[i] = data.size()
            if (s.length > 0x7fff) { w16((s.length ushr 16) or 0x8000); w16(s.length and 0xffff) } else w16(s.length)
            for (c in s) w16(c.code)
            w16(0)
        }
        while (data.size() % 4 != 0) data.write(0)
        val n = list.size
        val size = 28 + 4 * n + data.size()
        val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(CH_POOL.toShort()); bb.putShort(28); bb.putInt(size)
        bb.putInt(n); bb.putInt(0); bb.putInt(0); bb.putInt(28 + 4 * n); bb.putInt(0)
        for (o in offs) bb.putInt(o)
        bb.put(data.toByteArray())
        return bb.array()
    }
}
