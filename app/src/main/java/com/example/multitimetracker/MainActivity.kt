// v8
package com.example.multitimetracker

import android.Manifest
import android.content.Intent
import android.content.Context
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.multitimetracker.export.BackupFolderStore
import com.example.multitimetracker.ui.AppRoot
import com.example.multitimetracker.ui.theme.MultiTimeTrackerTheme
import com.example.multitimetracker.widget.QuickTaskWidgetProvider
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FOCUS_TASK_ID = "com.example.multitimetracker.EXTRA_FOCUS_TASK_ID"
    }

    private val focusTaskIdState = androidx.compose.runtime.mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        focusTaskIdState.value = intent?.getLongExtra(EXTRA_FOCUS_TASK_ID, -1L)
            ?.takeIf { it >= 0L }

        setContent {
            MultiTimeTrackerTheme {
                MultiTimeTrackerApp(focusTaskIdState)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = intent.getLongExtra(EXTRA_FOCUS_TASK_ID, -1L)
        if (id >= 0L) focusTaskIdState.value = id
    }
}

@Composable
private fun MultiTimeTrackerApp(
    focusTaskIdState: androidx.compose.runtime.MutableState<Long?>,
    vm: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by vm.state.collectAsState()

    // Track "tempo trascorso sull'app" (foreground only).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.onAppForeground()
                Lifecycle.Event.ON_PAUSE -> vm.onAppBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // If we start observing AFTER the Activity is already RESUMED (common on first composition),
        // we won't receive ON_RESUME and the "app usage" counter would stay frozen until the next resume.
        // So we eagerly sync the current state.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            vm.onAppForeground()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val scope = rememberCoroutineScope()

    // --- First-run setup: pick a workspace folder for import + autoexport.
    val showImportPrompt = remember { mutableStateOf(false) }
    val setupInProgress = remember { mutableStateOf(false) }
    val setupDone = remember { mutableStateOf(false) }
    val pendingProbe = remember { mutableStateOf<MainViewModel.BackupProbeResult?>(null) }

    val treePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            // User cancelled: keep prompting at next app start.
            setupInProgress.value = false
            return@rememberLauncherForActivityResult
        }
        setupInProgress.value = true
        vm.setBackupRootFolder(context, uri)
        val probe = vm.probeBackupFolder(context)
        pendingProbe.value = probe
        showImportPrompt.value = probe.hasValidFullSet
        if (!probe.hasValidFullSet) {
            // No previous data (or not valid) -> proceed.
            setupDone.value = true
            setupInProgress.value = false
        }
    }

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
        // Provide context early for setup flows that may persist/import before initialize().
        vm.bindContext(context)

        // Setup phase (folder pick + optional import/cleanup) must happen BEFORE initialize,
        // otherwise demo/empty data would be created and autoexport could race.
        if (BackupFolderStore.getTreeUri(context) == null) {
            setupInProgress.value = true
            treePickerLauncher.launch(null)
        } else {
            setupDone.value = true
        }
        if (Build.VERSION.SDK_INT >= 33 && !permissionGranted.value) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(setupDone.value) {
        if (setupDone.value) vm.initialize(context)
    }


// Listen for snapshot changes emitted by the widget while the app is open.
val latestVm = rememberUpdatedState(vm)
DisposableEffect(Unit) {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == QuickTaskWidgetProvider.ACTION_SNAPSHOT_CHANGED) {
                latestVm.value.reloadFromSnapshot(context)
            }
        }
    }
    val filter = IntentFilter(QuickTaskWidgetProvider.ACTION_SNAPSHOT_CHANGED)
    if (Build.VERSION.SDK_INT >= 33) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("DEPRECATION")
        context.registerReceiver(receiver, filter)
    }
    onDispose {
        runCatching { context.unregisterReceiver(receiver) }
    }
}

    if (showImportPrompt.value) {
        val probe = pendingProbe.value
        AlertDialog(
            onDismissRequest = {
                // Force a choice to avoid accidental silent behavior.
            },
            title = { Text("Import dati trovati?") },
            text = {
                Text(
                    "Ho trovato i CSV di una precedente installazione nella cartella dati. " +
                        "Vuoi importarli ora?\n\n" +
                        "File: ${probe?.presentFiles?.joinToString(", ") ?: ""}"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showImportPrompt.value = false
                    setupInProgress.value = true
                    scope.launch {
                        val ok = runCatching { vm.importBackupBlocking(context) }.isSuccess
                        if (ok) {
                            Toast.makeText(context, "Import completato", Toast.LENGTH_LONG).show()
                            setupDone.value = true
                        } else {
                            Toast.makeText(context, "Import fallito", Toast.LENGTH_LONG).show()
                            // Keep setup incomplete so the user can retry later.
                        }
                        setupInProgress.value = false
                    }
                }) {
                    Text("Sì, importa")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportPrompt.value = false
                    setupInProgress.value = true
                    scope.launch {
                        vm.deleteBackupCsv(context)
                        Toast.makeText(context, "Dati precedenti eliminati", Toast.LENGTH_LONG).show()
                        setupDone.value = true
                        setupInProgress.value = false
                    }
                }) {
                    Text("No, elimina")
                }
            }
        )
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

    AppRoot(
        vm = vm,
        focusTaskId = focusTaskIdState.value,
        onFocusConsumed = { focusTaskIdState.value = null }
    )
}
