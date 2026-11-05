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
import com.astrocam.app.camera.CameraDetector
import com.astrocam.app.camera.ProfileStore
import com.astrocam.app.camera.appVersion
import com.astrocam.app.camera.buildProfile
import com.astrocam.app.camera.controlLimits
import com.astrocam.app.camera.deviceName
import com.astrocam.app.ui.AstroScreen
import com.astrocam.app.ui.AstroTheme
import com.astrocam.app.ui.HomeScreen
import com.astrocam.app.ui.NightTheme
import com.astrocam.app.ui.ProbeScreen
import com.astrocam.app.ui.SettingsScreen
import com.astrocam.app.ui.TesterScreen

private enum class Screen { Home, Astro, Tester, Settings, Probe }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AstroApp() }
    }
}

@Composable
private fun AstroApp() {
    val context = LocalContext.current
    val cameras = remember { CameraDetector.detect(context) }
    var limits by remember { mutableStateOf(ProfileStore.load(context)) }
    val profile = remember(cameras, limits) {
        buildProfile(deviceName(), appVersion(context), cameras, limits)
    }
    val limitsForControls = remember(cameras, profile) { controlLimits(cameras, profile) }
    var night by rememberSaveable { mutableStateOf(NightTheme.Dark) }
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }

    AstroTheme(night) {
        BackHandler(enabled = screen != Screen.Home) {
            screen = if (screen == Screen.Probe) Screen.Settings else Screen.Home
        }
        when (screen) {
            Screen.Home -> HomeScreen(
                cameras = cameras,
                profile = profile,
                onOpenAstro = { screen = Screen.Astro },
                onOpenTester = { screen = Screen.Tester },
                onOpenSettings = { screen = Screen.Settings },
            )
            Screen.Astro -> AstroScreen(limits = limitsForControls, onBack = { screen = Screen.Home })
            Screen.Tester -> TesterScreen(cameras = cameras, limits = limits, onBack = { screen = Screen.Home })
            Screen.Settings -> SettingsScreen(
                night = night,
                onNightChange = { night = if (it) NightTheme.Red else NightTheme.Dark },
                profile = profile,
                onResetProfile = { limits = ProfileStore.reset(context) },
                onOpenProbe = { screen = Screen.Probe },
                onBack = { screen = Screen.Home },
            )
            Screen.Probe -> ProbeScreen(
                cameras = cameras,
                onBack = { screen = Screen.Settings },
                onSaved = { limits = it },
            )
        }
    }
}
