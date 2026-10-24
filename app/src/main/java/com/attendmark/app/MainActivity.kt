package com.attendmark.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.attendmark.app.data.FirebaseEmulatorConfig
import com.attendmark.app.ui.navigation.AttendMarkApp
import com.attendmark.app.ui.theme.AttendMarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseEmulatorConfig.setup()
        enableEdgeToEdge()
        setContent {
            AttendMarkTheme {
                AttendMarkApp()
            }
        }
    }
}
