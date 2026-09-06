package com.serein.reader

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import com.serein.reader.ui.SereinApp

class MainActivity : ComponentActivity() {
    var volumePageHandler: ((direction: Int) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SereinApp() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> -1
            KeyEvent.KEYCODE_VOLUME_DOWN -> 1
            else -> 0
        }
        if (direction != 0 && volumePageHandler?.invoke(direction) == true) return true
        return super.onKeyDown(keyCode, event)
    }
}
