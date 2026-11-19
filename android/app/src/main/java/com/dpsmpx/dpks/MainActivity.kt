package com.dpsmpx.dpks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.dpsmpx.dpks.ui.navigation.DpksApp
import com.dpsmpx.dpks.ui.theme.DpksTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DpksTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DpksApp()
                }
            }
        }
    }
}
