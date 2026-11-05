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
import com.astrocam.app.ui.AstroTheme
import com.astrocam.app.ui.HomeScreen
import com.astrocam.app.ui.NightTheme
import com.astrocam.app.ui.ProbeScreen
import com.astrocam.app.ui.TesterScreen

private enum class Screen { Home, Tester, Probe }

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
    var night by rememberSaveable { mutableStateOf(NightTheme.Dark) }
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }

    AstroTheme(night) {
        BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
        when (screen) {
            Screen.Home -> HomeScreen(
                cameras = cameras,
                night = night,
                onNightChange = { night = if (it) NightTheme.Red else NightTheme.Dark },
                onOpenTester = { screen = Screen.Tester },
                onOpenProbe = { screen = Screen.Probe },
            )
            Screen.Tester -> TesterScreen(cameras = cameras, onBack = { screen = Screen.Home })
            Screen.Probe -> ProbeScreen(cameras = cameras, onBack = { screen = Screen.Home })
        }
    }
}
