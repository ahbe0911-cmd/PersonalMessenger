package com.personal.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.personal.messenger.ui.LoginScreen
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
                DisposableEffect(vm) {
                    val observer = LifecycleEventObserver { _, event ->
                        if(event == Lifecycle.Event.ON_START) vm.foreground(true)
                        if(event == Lifecycle.Event.ON_STOP) vm.foreground(false)
                    }
                    lifecycle.addObserver(observer)
                    vm.foreground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                    onDispose { lifecycle.removeObserver(observer); vm.foreground(false) }
                }
                MessengerTheme(dark) {
                    if(state.deviceName == null) LoginScreen(state,vm) else ChatScreen(state,vm,dark)
                }
            }
        }
    }
}
