package com.cloneforge.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.cloneforge.data.AppEntry
import com.cloneforge.data.isValidPackage

@Composable
private fun AppIcon(app: AppEntry, size: Int = 44) {
    val bmp = remember(app.packageName, app.apkPaths) { app.icon?.toBitmap(96, 96)?.asImageBitmap() }
    if (bmp != null) Image(bmp, null, Modifier.size(size.dp))
    else Spacer(Modifier.size(size.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(vm: AppViewModel, onPickApk: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("CloneForge") }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onPickApk) { Text("Clone from APK file") } }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.query = it },
                label = { Text("Search apps") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            vm.status?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primary) }
            if (vm.loading) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            } else {
                val q = vm.query.trim().lowercase()
                val shown = if (q.isEmpty()) vm.apps else vm.apps.filter {
                    it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(shown, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.select(app) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(app)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsScreen(vm: AppViewModel, onCreate: () -> Unit) {
    val app = vm.selected ?: return
    val o = vm.options ?: return
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clone ${app.label}") },
                navigationIcon = { TextButton(onClick = { vm.back() }) { Text("Back") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, 56); Spacer(Modifier.width(12.dp))
                Column { Text(app.label, style = MaterialTheme.typography.titleMedium); Text(app.packageName, style = MaterialTheme.typography.bodySmall) }
            }
            OutlinedTextField(o.name, { vm.update(o.copy(name = it)) }, label = { Text("Clone name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                o.packageName, { vm.update(o.copy(packageName = it.trim())) }, label = { Text("Clone package name") },
                isError = !isValidPackage(o.packageName), singleLine = true, modifier = Modifier.fillMaxWidth()
            )

            SwitchRow("Disable app backups", o.disableBackup) { vm.update(o.copy(disableBackup = it)) }
            SwitchRow("Exclude from recents", o.excludeRecents) { vm.update(o.copy(excludeRecents = it)) }
            SwitchRow("Disable auto-start (boot receivers)", o.noAutoStart) { vm.update(o.copy(noAutoStart = it)) }
            SwitchRow("Remove widgets", o.noWidgets) { vm.update(o.copy(noWidgets = it)) }

            if (app.permissions.isNotEmpty()) {
                Text("Remove permissions", style = MaterialTheme.typography.titleSmall)
                app.permissions.sorted().forEach { p ->
                    SwitchRow(p.removePrefix("android.permission."), p in o.removedPermissions) { on ->
                        vm.update(o.copy(removedPermissions = if (on) o.removedPermissions + p else o.removedPermissions - p))
                    }
                }
            }

            vm.status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Button(onClick = onCreate, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.busy) "Working…" else "Create clone")
            }
            Text(
                "Google sign-in, Play Services and in-app purchases do not work in clones: " +
                    "cloning changes the app's signing certificate.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}
