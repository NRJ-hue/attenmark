package com.attendmark.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.attendmark.app.ui.navigation.AttendMarkApp
import com.attendmark.app.ui.theme.AttendMarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AttendMarkTheme {
                AttendMarkApp()
            }
        }
    }
}
