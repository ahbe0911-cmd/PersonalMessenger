package com.personal.messenger

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
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
    private var sharedUris by mutableStateOf<List<Uri>>(emptyList())
    private var sharedText by mutableStateOf<String?>(null)

    @Suppress("DEPRECATION")
    private fun acceptShare(intent: Intent?) {
        if(intent == null || intent.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        val streams = if(intent.action == Intent.ACTION_SEND_MULTIPLE)
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        val clips = (0 until (intent.clipData?.itemCount ?: 0)).mapNotNull { intent.clipData?.getItemAt(it)?.uri }
        sharedUris = (streams + clips).distinct().filter { it.scheme == "content" }.take(30)
        sharedText = if(sharedUris.isEmpty()) intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(16000) else null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShare(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList("sharedUris", ArrayList(sharedUris))
        outState.putString("sharedText", sharedText)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(savedInstanceState == null) acceptShare(intent)
        else {
            @Suppress("DEPRECATION")
            sharedUris = savedInstanceState.getParcelableArrayList<Uri>("sharedUris").orEmpty()
            sharedText = savedInstanceState.getString("sharedText")
        }
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val vm: ChatViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val dark = state.dark ?: isSystemInDarkTheme()
            val askNotifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
            LaunchedEffect(state.deviceName){
                if(state.deviceName!=null&&Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
                    val p=getSharedPreferences("appearance",0)
                    if(!p.getBoolean("asked_notifications",false)){p.edit().putBoolean("asked_notifications",true).apply();askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)}
                }
            }
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
                    if(state.deviceName == null) LoginScreen(state,vm) else {
                        ChatScreen(state,vm,dark)
                        if((sharedUris.isNotEmpty() || sharedText != null) && !state.busy) {
                            val uri = sharedUris.firstOrNull()
                            val name = remember(uri) {
                                uri?.let { u -> runCatching {
                                    contentResolver.query(u,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c -> if(c.moveToFirst()) c.getString(0) else null }
                                }.getOrNull() } ?: "فایل اشتراک‌گذاری‌شده"
                            }
                            AlertDialog(
                                onDismissRequest={sharedUris=emptyList();sharedText=null},
                                title={Text(if(uri!=null)"ارسال فایل اشتراک‌گذاری‌شده" else "اشتراک‌گذاری متن")},
                                text={Column{
                                    Text(if(uri!=null)name else sharedText.orEmpty(),maxLines=4)
                                    if(sharedUris.size>1)Text(sharedUris.size.toString()+" فایل در صف انتخاب؛ هر فایل جداگانه تأیید می‌شود.",style=MaterialTheme.typography.bodySmall)
                                }},
                                confirmButton={TextButton(onClick={
                                    if(uri!=null){sharedUris=sharedUris.drop(1);vm.attach(uri)}
                                    else{vm.draft(sharedText.orEmpty());sharedText=null}
                                }){Text(if(uri!=null)"ارسال" else "درج در کادر پیام")}},
                                dismissButton={TextButton(onClick={sharedUris=emptyList();sharedText=null}){Text("لغو")}}
                            )
                        }
                    }
                }
            }
        }
    }
}
