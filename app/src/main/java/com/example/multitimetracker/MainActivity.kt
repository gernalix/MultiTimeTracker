// v2
package com.example.multitimetracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.viewModels
import com.example.multitimetracker.ui.AppRoot
import com.example.multitimetracker.ui.theme.MultiTimeTrackerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val requestPostNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android 13+: without this, notifications (including foreground service) may be hidden.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Keep the app warm ONLY while at least one task is running.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { s ->
                    val running = s.tasks.count { it.isRunning }
                    if (running > 0) {
                        TrackingForegroundService.startIfNeeded(this@MainActivity)
                    } else {
                        TrackingForegroundService.stopIfRunning(this@MainActivity)
                    }
                }
            }
        }

        setContent {
            MultiTimeTrackerTheme {
                MultiTimeTrackerApp(vm)
            }
        }
    }
}

@Composable
private fun MultiTimeTrackerApp(vm: MainViewModel) {
    AppRoot(vm = vm)
}
