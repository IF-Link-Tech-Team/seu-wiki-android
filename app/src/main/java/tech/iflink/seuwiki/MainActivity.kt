package tech.iflink.seuwiki

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import tech.iflink.seuwiki.design.SEUWikiTheme
import tech.iflink.seuwiki.ui.RootView

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SEUWikiTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RootView()
                }
            }
        }
    }
}
