package com.personal.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.personal.messenger.model.ChatState
import com.personal.messenger.viewmodel.ChatViewModel

@Composable
fun LoginScreen(state: ChatState, vm: ChatViewModel) {
    var name by remember { mutableStateOf("گوشی اندروید") }
    var secret by remember { mutableStateOf("") }
    Scaffold(containerColor=MaterialTheme.colorScheme.background,contentWindowInsets=WindowInsets.safeDrawing) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding().padding(20.dp),contentAlignment=Alignment.Center) {
            if(state.initializing) CircularProgressIndicator()
            else Card(shape=RoundedCornerShape(22.dp),modifier=Modifier.widthIn(max=440.dp).fillMaxWidth()) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(68.dp).background(Color(0xFF008069),CircleShape),contentAlignment=Alignment.Center) {
                        Text("م",color=Color.White,style=MaterialTheme.typography.headlineLarge)
                    }
                    Text("پیام‌رسان من",style=MaterialTheme.typography.headlineSmall)
                    Text("برای همگام‌سازی با کامپیوتر، کلید ثبت دستگاه خود را وارد کنید.",textAlign=TextAlign.Center)
                    OutlinedTextField(name,{name=it.take(80)},label={Text("نام دستگاه")},singleLine=true,
                        enabled=!state.busy,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(secret,{secret=it.take(128)},label={Text("کلید ثبت دستگاه")},singleLine=true,
                        visualTransformation=PasswordVisualTransformation(),enabled=!state.busy,modifier=Modifier.fillMaxWidth())
                    if(state.error!=null) Text(state.error,color=MaterialTheme.colorScheme.error,textAlign=TextAlign.Center)
                    Button(onClick={vm.login(name,secret);secret=""},enabled=!state.busy&&name.isNotBlank()&&secret.trim().length>=32,
                        modifier=Modifier.fillMaxWidth()) {
                        if(state.busy) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp,color=MaterialTheme.colorScheme.onPrimary)
                        else Text("اتصال امن")
                    }
                    Text("کلید ثبت دستگاه ذخیره نمی‌شود.",style=MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
