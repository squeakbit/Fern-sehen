package de.example.timelapse

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import de.example.timelapse.service.CameraForegroundService
import de.example.timelapse.ui.CameraTab
import de.example.timelapse.ui.HomeTab
import de.example.timelapse.ui.SettingsTab
import de.example.timelapse.ui.theme.TimelapseTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val mediaPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }

        cameraPermission.launch(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 34) {
            mediaPermission.launch(
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
            )
        } else if (Build.VERSION.SDK_INT >= 33) {
            mediaPermission.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES))
        } else if (Build.VERSION.SDK_INT >= 29) {
            storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        setContent { TimelapseTheme { AppRoot() } }
        handleWakeupIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeupIntent(intent)
    }

    private fun handleWakeupIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("EXTRA_ALARM_CAPTURE", false) == true) {
            // Set window brightness to minimum (0.01f) so the screen stays dark
            // during the automated background capture process on older APIs like Android 9.
            val lp = window.attributes
            lp.screenBrightness = 0.01f
            window.attributes = lp

            // Ensure camera service is running while activity has foreground privileges (Android 14+ fix)
            try {
                CameraForegroundService.ensureServiceRunning(this)
                CameraForegroundService.nudge()
            } catch (t: Throwable) {
                Log.w("Timelapse", "Failed to ensure camera service running from wakeup activity", t)
            }

            lifecycleScope.launch {
                delay(8000)
                if (!isFinishing) {
                    try { 
                        moveTaskToBack(true) 
                    } catch (_: Throwable) {}
                    
                    // Restore default brightness when moving to the background
                    val lpRestore = window.attributes
                    lpRestore.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    window.attributes = lpRestore
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        CameraForegroundService.ensureServiceRunning(this)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppRoot() {
        var tab by remember { mutableIntStateOf(0) }
        var liveEnabled by remember { mutableStateOf(false) }
        var showGhost by remember { mutableStateOf(false) }
        
        // Auto-disable Live View and Ghost when leaving the Camera tab (tab 1)
        LaunchedEffect(tab) {
            if (tab != 1) {
                liveEnabled = false
                showGhost = false
            }
        }

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) {
                    liveEnabled = false
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_start)) }, icon = { Icon(Icons.Default.PlayArrow, null) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_camera)) }, icon = { Icon(Icons.Default.CameraAlt, null) })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.tab_setup)) }, icon = { Icon(Icons.Default.Settings, null) })
                }
                when (tab) {
                    0 -> HomeTab(
                        onEnsureCameraServiceRunning = { CameraForegroundService.ensureServiceRunning(this@MainActivity) }
                    )
                    1 -> CameraTab(
                        liveEnabled = liveEnabled, 
                        onLiveEnabledChange = { liveEnabled = it },
                        showGhost = showGhost,
                        onShowGhostChange = { showGhost = it }
                    )
                    else -> SettingsTab(
                        onRequestIgnoreBatteryOptimizations = { requestIgnoreBatteryOptimizations() },
                        onRequestExactAlarmPermission = { requestExactAlarmPermission() }
                    )
                }
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}
