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
        
        // 启动时检查更新
        com.example.mytransl.utils.UpdateManager.checkUpdate(this) { versionName, changelog, url ->
            // 这里可以触发一个 Compose 的状态来显示 Dialog
            // 简单起见，这里可以先用 Log 或 Toast，或者在 setContent 里处理
            android.util.Log.d("Update", "New version found: $versionName")
        }

        enableEdgeToEdge()
        setContent {
            MyTranslTheme {
                AppNav()
            }
        }
    }
}
