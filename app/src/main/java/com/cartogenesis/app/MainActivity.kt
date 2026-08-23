package com.cartogenesis.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cartogenesis.app.ui.MapViewModel
import com.cartogenesis.app.ui.screens.CartogenesisApp
import com.cartogenesis.app.ui.theme.CartogenesisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CartogenesisTheme {
                val viewModel: MapViewModel = viewModel()
                CartogenesisApp(viewModel)
            }
        }
    }
}
