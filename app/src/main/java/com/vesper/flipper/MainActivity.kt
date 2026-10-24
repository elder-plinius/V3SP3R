package com.vesper.flipper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.ui.navigation.VesperApp
import com.vesper.flipper.ui.navigation.VesperDestination
import com.vesper.flipper.ui.onboarding.OnboardingScreen
import com.vesper.flipper.ui.onboarding.OnboardingState
import com.vesper.flipper.ui.theme.VesperTheme
import com.vesper.flipper.ui.theme.vesperBackdrop
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Single activity. Edge-to-edge, no permission dialog fired before the
 * onboarding screen has explained what it is for.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsStore: SettingsStore

    private var blePermissionGranted by mutableStateOf(false)
    private var pendingRoute by mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        blePermissionGranted = hasBlePermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        blePermissionGranted = hasBlePermissions()
        pendingRoute = routeFromIntent(intent)?.takeIf { OnboardingState.isCompleted(this) }

        setContent {
            val darkMode by settingsStore.darkMode.collectAsState(initial = true)
            var completed by remember { mutableStateOf(OnboardingState.isCompleted(this)) }
            var startRoute by remember {
                mutableStateOf(pendingRoute ?: VesperDestination.Chat.route)
            }

            // Resolve the existing key once: returning users must not be sent
            // through onboarding again after an update.
            val keyState by produceState<Pair<Boolean, String?>>(initialValue = false to null) {
                val key = runCatching { settingsStore.apiKey.first() }.getOrNull()
                value = true to key
            }
            val keyResolved = keyState.first
            val savedKey = keyState.second

            VesperTheme(darkTheme = darkMode) {
                LaunchedEffect(darkMode) {
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    controller.isAppearanceLightStatusBars = !darkMode
                    controller.isAppearanceLightNavigationBars = !darkMode
                }
                LaunchedEffect(keyResolved, savedKey) {
                    if (keyResolved && !savedKey.isNullOrBlank() && !completed) {
                        OnboardingState.markCompleted(this@MainActivity)
                        completed = true
                    }
                }

                val showOnboarding = keyResolved && !completed && savedKey.isNullOrBlank()
                if (!keyResolved && !completed) {
                    // A few frames while the key is read: show the canvas, not
                    // a flash of the chat screen.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(vesperBackdrop())
                    )
                } else if (showOnboarding) {
                    OnboardingScreen(
                        blePermissionGranted = blePermissionGranted,
                        onRequestBlePermissions = ::requestBlePermissions,
                        onFinish = { openSettings ->
                            OnboardingState.markCompleted(this@MainActivity)
                            completed = true
                            startRoute = if (openSettings) {
                                VesperDestination.Settings.route
                            } else {
                                VesperDestination.Chat.route
                            }
                        }
                    )
                } else {
                    VesperApp(
                        blePermissionGranted = blePermissionGranted,
                        onRequestBlePermissions = ::requestBlePermissions,
                        startRoute = startRoute,
                        routeRequest = pendingRoute,
                        onRouteRequestHandled = { pendingRoute = null }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        blePermissionGranted = hasBlePermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeFromIntent(intent)?.let { pendingRoute = it }
    }

    /** Maps vesper://open/{route} to a destination route. */
    private fun routeFromIntent(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "vesper") return null
        return VesperDestination.byRoute(data.lastPathSegment)?.route
    }

    private fun requestBlePermissions() {
        permissionLauncher.launch(blePermissions())
    }

    private fun blePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private fun hasBlePermissions(): Boolean = blePermissions().all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}
