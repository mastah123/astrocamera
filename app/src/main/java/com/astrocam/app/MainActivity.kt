package com.astrocam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.astrocam.app.camera.AppSettings
import com.astrocam.app.camera.CameraDetector
import com.astrocam.app.camera.CrashLog
import com.astrocam.app.camera.ProfileStore
import com.astrocam.app.camera.SettingsStore
import com.astrocam.app.camera.appVersion
import com.astrocam.app.camera.buildProfile
import com.astrocam.app.camera.controlLimits
import com.astrocam.app.camera.deviceName
import com.astrocam.app.ui.AstroScreen
import com.astrocam.app.ui.AstroTheme
import com.astrocam.app.ui.HomeScreen
import com.astrocam.app.ui.NightTheme
import com.astrocam.app.ui.ProbeScreen
import com.astrocam.app.ui.RawScreen
import com.astrocam.app.ui.SettingsScreen
import com.astrocam.app.ui.StackScreen
import com.astrocam.app.ui.TesterScreen

private enum class Screen { Home, Astro, Stack, Tester, Settings, Probe, Raw }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(applicationContext)
        enableEdgeToEdge()
        setContent { AstroApp() }
    }
}

@Composable
private fun AstroApp() {
    val context = LocalContext.current
    val cameras = remember { CameraDetector.detect(context) }
    var limits by remember { mutableStateOf(ProfileStore.load(context)) }
    var rawResults by remember { mutableStateOf(ProfileStore.loadRaw(context)) }
    val profile = remember(cameras, limits, rawResults) {
        buildProfile(deviceName(), appVersion(context), cameras, limits, rawResults)
    }
    val limitsForControls = remember(cameras, profile) { controlLimits(cameras, profile) }

    // Settings are saved as soon as they change, so they survive closing the app.
    var settings by remember { mutableStateOf(SettingsStore.load(context)) }
    val updateSettings: (AppSettings) -> Unit = {
        settings = it
        SettingsStore.save(context, it)
    }
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }

    AstroTheme(if (settings.redMode) NightTheme.Red else NightTheme.Dark) {
        BackHandler(enabled = screen != Screen.Home) {
            screen = if (screen == Screen.Probe || screen == Screen.Raw) Screen.Settings else Screen.Home
        }
        when (screen) {
            Screen.Home -> HomeScreen(
                cameras = cameras,
                profile = profile,
                onOpenAstro = { screen = Screen.Astro },
                onOpenStack = { screen = Screen.Stack },
                onOpenTester = { screen = Screen.Tester },
                onOpenSettings = { screen = Screen.Settings },
            )
            Screen.Astro -> AstroScreen(
                limits = limitsForControls,
                settings = settings,
                onSettings = updateSettings,
                onOpenStack = { screen = Screen.Stack },
                onBack = { screen = Screen.Home },
            )
            Screen.Stack -> StackScreen(settings = settings, onBack = { screen = Screen.Home })
            Screen.Tester -> TesterScreen(cameras = cameras, profile = profile, onBack = { screen = Screen.Home })
            Screen.Settings -> SettingsScreen(
                settings = settings,
                onSettings = updateSettings,
                profile = profile,
                onResetProfile = {
                    limits = ProfileStore.reset(context)
                    rawResults = emptyMap()
                },
                onOpenProbe = { screen = Screen.Probe },
                onOpenRaw = { screen = Screen.Raw },
                onBack = { screen = Screen.Home },
            )
            Screen.Probe -> ProbeScreen(
                cameras = cameras,
                onBack = { screen = Screen.Settings },
                onSaved = { limits = it },
            )
            Screen.Raw -> RawScreen(
                cameras = cameras,
                onBack = { screen = Screen.Settings },
                onSaved = { rawResults = it },
            )
        }
    }
}
