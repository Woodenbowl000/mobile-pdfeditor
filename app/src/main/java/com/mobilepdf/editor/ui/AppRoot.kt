@file:OptIn(ExperimentalMaterial3Api::class)

package com.mobilepdf.editor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mobilepdf.editor.AppViewModel
import com.mobilepdf.editor.Screen
import com.mobilepdf.editor.ui.home.HomeScreen
import com.mobilepdf.editor.ui.organize.OrganizeScreen
import com.mobilepdf.editor.ui.tools.ToolsScreen
import com.mobilepdf.editor.ui.viewer.ViewerScreen

/**
 * Navigation is a single enum in the ViewModel (no nav library): fewer dependencies, smaller APK,
 * and screen state survives configuration changes for free.
 */
@Composable
fun AppRoot(vm: AppViewModel) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(I18n.tr(it)); vm.message = null }
    }

    // Urdu reads right to left: the whole interface mirrors (the PDF pages keep their own direction)
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides if (I18n.urdu) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.platform.LocalLayoutDirection.current,
    ) {
    Box(Modifier.fillMaxSize()) {
        when (vm.screen) {
            Screen.HOME, Screen.TOOLS, Screen.SETTINGS -> MainTabs(vm, snack)
            // one viewer state per document: opening another PDF (e.g. from a file manager) must not carry
            // over the previous document's annotations or dialogs (its unsaved work stays in its draft)
            Screen.VIEWER -> androidx.compose.runtime.key(vm.doc?.let(vm::draftKey)) { ViewerScreen(vm, snack) }
            Screen.ORGANIZE -> OrganizeScreen(vm)
        }
        // Snackbar for full-screen screens (Scaffold handles its own in MainTabs)
        if (vm.screen == Screen.VIEWER || vm.screen == Screen.ORGANIZE) {
            SnackbarHost(
                snack,
                // above the system navigation bar and the editing toolbar
                Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                    .then(Modifier.navigationBarsPadding()).padding(bottom = 120.dp),
            )
        }
        if (vm.budget.simulateLowEnd) LowEndMeter(vm.budget, Modifier.align(androidx.compose.ui.Alignment.TopEnd))
        BusyOverlay(vm.busy)
        vm.pendingPassword?.let { p ->
            PasswordDialog(p.name, p.wrong, onSubmit = vm::submitPassword, onCancel = { vm.pendingPassword = null })
        }
        if (vm.result != null) ResultSheet(vm) { vm.result = null }
    }
    }
}

@Composable
private fun MainTabs(vm: AppViewModel, snack: SnackbarHostState) {
    BackHandler(vm.screen == Screen.TOOLS || vm.screen == Screen.SETTINGS) { vm.screen = Screen.HOME }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = vm.screen == Screen.HOME, onClick = { vm.screen = Screen.HOME },
                    icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = vm.screen == Screen.TOOLS, onClick = { vm.screen = Screen.TOOLS },
                    icon = { Icon(Icons.Filled.Build, null) }, label = { Text("Tools") },
                )
                NavigationBarItem(
                    selected = vm.screen == Screen.SETTINGS, onClick = { vm.screen = Screen.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (vm.screen) {
                Screen.HOME -> HomeScreen(vm)
                Screen.SETTINGS -> com.mobilepdf.editor.ui.settings.SettingsScreen(vm)
                else -> ToolsScreen(vm)
            }
        }
    }
}
