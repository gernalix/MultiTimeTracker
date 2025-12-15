// v1
package com.example.multitimetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    AppRoot(vm = vm)
}
