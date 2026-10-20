package com.cloneforge.engine

/** What to change in a cloned app's manifest. */
data class ManifestPatch(
    val newPackage: String? = null,
    val label: String? = null,
    val removePermissions: Set<String> = emptySet(),
    val allowBackup: Boolean? = null,          // null = leave as is
    val excludeFromRecents: Boolean = false,
    val removeBootReceivers: Boolean = false,  // "disable auto-start"
    val removeWidgets: Boolean = false,
    val stripSharedUserId: Boolean = true
)

object Res {
    const val LABEL = 0x01010001
    const val NAME = 0x01010003
    const val EXCLUDE_FROM_RECENTS = 0x01010017
    const val ALLOW_BACKUP = 0x01010280
}

private val BOOT_ACTIONS = setOf(
    "android.intent.action.BOOT_COMPLETED",
    "android.intent.action.LOCKED_BOOT_COMPLETED",
    "android.intent.action.QUICKBOOT_POWERON",
    "com.htc.intent.action.QUICKBOOT_POWERON"
)
private const val WIDGET_ACTION = "android.appwidget.action.APPWIDGET_UPDATE"
private val COMPONENT_TAGS = setOf("application", "activity", "activity-alias", "service", "receiver", "provider")

class ManifestPatcher(private val doc: Axml) {

    // ---------------------------------------------------------------- helpers
    private fun StartEl.attr(name: String, android: Boolean = true): Attr? =
        attrs.firstOrNull { it.name == name && (if (android) it.ns == ANDROID_NS else it.ns == null) }

    private fun StartEl.str(name: String) = attr(name)?.str

    private fun endOf(start: Int): Int {
        var depth = 0
        for (j in start until doc.events.size) {
            val e = doc.events[j]
            if (e is StartEl) depth++ else if (e is EndEl) { depth--; if (depth == 0) return j }
        }
        error("Unbalanced manifest")
    }

    private fun starts(vararg tags: String): List<Int> =
        doc.events.indices.filter { val e = doc.events[it]; e is StartEl && e.name in tags }

    private fun actionsIn(start: Int, end: Int): Set<String> {
        val out = HashSet<String>()
        for (j in start..end) {
            val e = doc.events[j]
            if (e is StartEl && e.name == "action") e.str("name")?.let { out.add(it) }
        }
        return out
    }

    private fun removeRanges(ranges: List<IntRange>) {
        for (r in ranges.sortedByDescending { it.first }) for (k in r.last downTo r.first) doc.events.removeAt(k)
    }

    /** Insert keeping aapt's ordering: attributes with a resource id sorted ascending, id-less ones last. */
    private fun setAttr(el: StartEl, name: String, resId: Int, type: Int, data: Int, str: String?) {
        val existing = el.attr(name)
        if (existing != null) {
            existing.type = type; existing.data = data; existing.str = str; existing.raw = null
            return
        }
        val a = Attr(ANDROID_NS, name, resId, type, data, str, null)
        var pos = el.attrs.indexOfFirst { it.resId == 0 || it.resId > resId }
        if (pos < 0) pos = el.attrs.size
        el.attrs.add(pos, a)
    }

    private fun setBool(el: StartEl, name: String, resId: Int, v: Boolean) =
        setAttr(el, name, resId, TYPE_BOOLEAN, if (v) -1 else 0, null)

    private fun setString(el: StartEl, name: String, resId: Int, v: String) =
        setAttr(el, name, resId, TYPE_STRING, 0, v)

    // ---------------------------------------------------------------- public API
    fun packageName(): String = starts("manifest").map { doc.events[it] as StartEl }.first().attr("package", false)!!.str!!

    fun apply(p: ManifestPatch) {
        val oldPkg = packageName()
        // Relative class names (".Main") would silently re-resolve against the new package: pin them first.
        if (p.newPackage != null && p.newPackage != oldPkg) expandRelativeNames(oldPkg)

        if (p.stripSharedUserId) {
            val root = doc.events.first { it is StartEl && it.name == "manifest" } as StartEl
            root.attrs.removeAll { it.ns == ANDROID_NS && (it.name == "sharedUserId" || it.name == "sharedUserLabel") }
        }
        if (p.removePermissions.isNotEmpty()) removePermissions(p.removePermissions)
        if (p.removeBootReceivers) {
            removeReceiversWith(BOOT_ACTIONS)
            removePermissions(setOf("android.permission.RECEIVE_BOOT_COMPLETED"))
        }
        if (p.removeWidgets) removeReceiversWith(setOf(WIDGET_ACTION))
        if (p.allowBackup != null) {
            val app = doc.events.firstOrNull { it is StartEl && it.name == "application" } as StartEl?
            if (app != null) setBool(app, "allowBackup", Res.ALLOW_BACKUP, p.allowBackup)
        }
        if (p.excludeFromRecents) {
            for (i in starts("activity")) setBool(doc.events[i] as StartEl, "excludeFromRecents", Res.EXCLUDE_FROM_RECENTS, true)
        }
        if (p.label != null) applyLabel(p.label)
        if (p.newPackage != null && p.newPackage != oldPkg) renamePackage(oldPkg, p.newPackage)
    }

    // ---------------------------------------------------------------- operations
    private fun expandRelativeNames(pkg: String) {
        fun full(v: String): String = when {
            v.startsWith(".") -> pkg + v
            !v.contains('.') -> "$pkg.$v"
            else -> v
        }
        for (e in doc.events) {
            if (e !is StartEl) continue
            if (e.name in COMPONENT_TAGS) e.attr("name")?.let { a -> a.str?.let { a.str = full(it) } }
            if (e.name == "activity-alias") e.attr("targetActivity")?.let { a -> a.str?.let { a.str = full(it) } }
            if (e.name == "activity") e.attr("parentActivityName")?.let { a -> a.str?.let { a.str = full(it) } }
            if (e.name == "application") e.attr("backupAgent")?.let { a -> a.str?.let { a.str = full(it) } }
        }
    }

    private fun removePermissions(names: Set<String>) {
        val ranges = starts("uses-permission", "uses-permission-sdk-23").filter {
            (doc.events[it] as StartEl).str("name") in names
        }.map { it..endOf(it) }
        removeRanges(ranges)
    }

    private fun removeReceiversWith(actions: Set<String>) {
        val ranges = starts("receiver").mapNotNull {
            val end = endOf(it)
            if (actionsIn(it, end).any { a -> a in actions }) it..end else null
        }
        removeRanges(ranges)
    }

    private fun applyLabel(label: String) {
        val app = doc.events.firstOrNull { it is StartEl && it.name == "application" } as StartEl? ?: return
        setString(app, "label", Res.LABEL, label)
        for (i in starts("activity", "activity-alias")) {
            val el = doc.events[i] as StartEl
            val end = endOf(i)
            val launcher = (i..end).any { j ->
                val e = doc.events[j]
                e is StartEl && e.name == "category" && e.str("name") == "android.intent.category.LAUNCHER"
            }
            if (el.attr("label") != null || launcher) setString(el, "label", Res.LABEL, label)
        }
    }

    private fun renamePackage(old: String, new: String) {
        fun swapPrefix(v: String): String = if (v == old || v.startsWith("$old.")) new + v.removePrefix(old) else v
        for (e in doc.events) {
            if (e !is StartEl) continue
            when (e.name) {
                "manifest" -> e.attr("package", false)?.str = new
                "provider" -> e.attr("authorities")?.let { a -> a.str = a.str?.replace(old, new) }
                "permission", "permission-tree", "permission-group" -> e.attr("name")?.let { a -> a.str = a.str?.let(::swapPrefix) }
                "uses-permission", "uses-permission-sdk-23" -> e.attr("name")?.let { a -> a.str = a.str?.let(::swapPrefix) }
                "instrumentation" -> e.attr("targetPackage")?.let { a -> if (a.str == old) a.str = new }
            }
            for (n in arrayOf("permission", "readPermission", "writePermission", "taskAffinity")) {
                e.attr(n)?.let { a -> a.str = a.str?.let(::swapPrefix) }
            }
            if (e.name in COMPONENT_TAGS) e.attr("process")?.let { a -> a.str = a.str?.let { v -> if (v.startsWith(old)) swapPrefix(v) else v } }
        }
    }
}
