// v3
package com.example.multitimetracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.multitimetracker.ui.AppRoot
import com.example.multitimetracker.ui.theme.MultiTimeTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MultiTimeTrackerTheme {
                MultiTimeTrackerApp()
            }
        }
    }
}

@Composable
private fun MultiTimeTrackerApp(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()

    val permissionGranted = remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted.value = granted
    }

    LaunchedEffect(Unit) {
        // Restore persisted state (and auto-resume running timers) as soon as the UI starts.
        vm.initialize(context)
        if (Build.VERSION.SDK_INT >= 33 && !permissionGranted.value) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Foreground service "solo mentre traccio"
    val runningCount = state.tasks.count { it.isRunning }
    LaunchedEffect(runningCount, permissionGranted.value) {
        val svc = Intent(context, TrackingForegroundService::class.java)
        if (runningCount > 0 && permissionGranted.value) {
            ContextCompat.startForegroundService(context, svc)
        } else {
            context.stopService(svc)
        }
    }

    AppRoot(vm = vm)
}
