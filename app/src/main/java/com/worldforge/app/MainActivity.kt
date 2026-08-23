package com.worldforge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.worldforge.app.ui.MapViewModel
import com.worldforge.app.ui.screens.WorldforgeApp
import com.worldforge.app.ui.theme.WorldforgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            WorldforgeTheme {
                val viewModel: MapViewModel = viewModel()
                WorldforgeApp(viewModel)
            }
        }
    }
}
