package com.personal.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.messenger.ui.ChatScreen
import com.personal.messenger.ui.MessengerTheme
import com.personal.messenger.viewmodel.ChatViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: ChatViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val dark = state.dark ?: isSystemInDarkTheme()
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MessengerTheme(dark) { ChatScreen(state, vm, dark) }
            }
        }
    }
}
