package com.cloneforge.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF3D5AFE))) {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) vm.importApk(uri)
                }
                BackHandler(enabled = vm.selected != null) { vm.back() }

                if (vm.selected == null) {
                    AppListScreen(
                        vm = vm,
                        onPickApk = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }
                    )
                } else {
                    OptionsScreen(vm = vm, onCreate = {
                        if (!packageManager.canRequestPackageInstalls()) {
                            // One-time: let CloneForge install the clones it builds.
                            startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                            )
                        } else vm.createClone()
                    })
                }
            }
        }
    }
}
