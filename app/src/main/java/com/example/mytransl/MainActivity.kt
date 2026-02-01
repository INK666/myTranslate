package com.example.mytransl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.mytransl.ui.theme.MyTranslTheme
import com.example.mytransl.ui.nav.AppNav

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyTranslTheme {
                AppNav()
            }
        }
    }
}
