package com.cloneforge.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloneforge.data.AppEntry
import com.cloneforge.data.AppRepository
import com.cloneforge.data.CloneOptions
import com.cloneforge.data.isValidPackage
import com.cloneforge.engine.ApkInstaller
import com.cloneforge.engine.CloneEngine
import com.cloneforge.engine.InstallBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AppRepository(app)

    var apps by mutableStateOf<List<AppEntry>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var query by mutableStateOf("")
    var selected by mutableStateOf<AppEntry?>(null); private set
    var options by mutableStateOf<CloneOptions?>(null); private set
    var status by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set

    init {
        refresh()
        viewModelScope.launch { InstallBus.messages.collect { status = it; busy = false } }
    }

    fun refresh() {
        loading = true
        viewModelScope.launch {
            apps = withContext(Dispatchers.IO) { repo.loadInstalled() }
            loading = false
        }
    }

    fun select(e: AppEntry) {
        selected = e
        status = null
        options = CloneOptions(name = e.label, packageName = repo.suggestPackage(e.packageName))
    }

    fun back() { selected = null; options = null; status = null }
    fun update(o: CloneOptions) { options = o }

    fun importApk(uri: Uri) {
        viewModelScope.launch {
            status = "Reading APK…"
            val entry = withContext(Dispatchers.IO) {
                val dir = File(getApplication<Application>().cacheDir, "imports").apply { mkdirs() }
                val f = File(dir, "import_${System.currentTimeMillis()}.apk")
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { i ->
                    f.outputStream().use { o -> i.copyTo(o, 1 shl 16) }
                }
                repo.fromApk(f)
            }
            if (entry == null) status = "That file is not a valid APK" else select(entry)
        }
    }

    fun createClone() {
        val src = selected ?: return
        val o = options ?: return
        if (!isValidPackage(o.packageName)) { status = "Invalid package name"; return }
        if (o.packageName == src.packageName) { status = "Package name must differ from the original"; return }
        busy = true
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val files = withContext(Dispatchers.IO) { CloneEngine(ctx).build(src, o) { status = it } }
                status = "Installing…"
                withContext(Dispatchers.IO) { ApkInstaller.install(ctx, files) }
                // the result arrives through InstallBus
            } catch (t: Throwable) {
                status = "Failed: ${t.message ?: t.javaClass.simpleName}"
                busy = false
            }
        }
    }
}
