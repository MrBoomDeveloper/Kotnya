package com.mrboomdev.kotnya.sample

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            MaterialTheme(
                colorScheme = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if(isSystemInDarkTheme()) {
                        dynamicDarkColorScheme(this)
                    } else dynamicLightColorScheme(this)
                } else {
                    if(isSystemInDarkTheme()) {
                        darkColorScheme()
                    } else lightColorScheme()
                }
            ) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeContentPadding()) {
                        Text("Hello, World!")
                    }
                }
            }
        }
    }
}