package com.example.heyandroid.brief

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.heyandroid.ui.theme.HeyAndroidTheme

/**
 * The Brief page: the exact HTML page the widget shows, rendered live in a
 * WebView. Tapping the widget lands here.
 */
class BriefActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        setContent {
            HeyAndroidTheme(darkTheme = true) {
                BriefPage(onClose = { finish() })
            }
        }
    }
}
