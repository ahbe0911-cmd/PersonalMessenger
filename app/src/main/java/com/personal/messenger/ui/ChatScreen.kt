package com.personal.messenger.ui

import android.icu.text.SimpleDateFormat
import android.icu.util.TimeZone
import android.icu.util.ULocale
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.personal.messenger.model.*
import com.personal.messenger.viewmodel.ChatViewModel
import java.util.Date

private fun iranTime(ms: Long, pattern: String): String =
    SimpleDateFormat(pattern, ULocale("fa_IR@calendar=persian")).apply {
        timeZone = TimeZone.getTimeZone("Asia/Tehran")
    }.format(Date(ms))

@Composable
fun ChatScreen(state: ChatState, vm: ChatViewModel, dark: Boolean) {
    val colors = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    var searching by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); vm.dismissError() } }
    val visible = state.filtered
    LaunchedEffect(visible.lastOrNull()?.id) {
        if (visible.isNotEmpty()) list.animateScrollToItem(visible.lastIndex)
    }
    Scaffold(snackbarHost={ SnackbarHost(snackbar) }, containerColor=colors.background,
        contentWindowInsets=WindowInsets.safeDrawing) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Surface(color=colors.surface, tonalElevation=2.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal=14.dp, vertical=10.dp), verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(Color(0xFF008069), CircleShape), contentAlignment=Alignment.Center) {
                        Text("م", color=Color.White, style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("گفتگوی شخصی", style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.Bold)
                        Text(state.connection, style=MaterialTheme.typography.bodySmall, color=colors.onSurfaceVariant)
                    }
                    TextButton(onClick={ searching=!searching; if(!searching) vm.search("") }) { Text("جستجو") }
                    Box { IconButton(onClick={menu=true}) { Text("⋮",style=MaterialTheme.typography.headlineSmall) }; DropdownMenu(expanded=menu,onDismissRequest={menu=false}) { DropdownMenuItem(text={Text("همگام‌سازی")},onClick={menu=false;vm.refresh()}); DropdownMenuItem(text={Text(if(dark) "حالت روشن" else "حالت تاریک")},onClick={menu=false;vm.theme(dark)}); DropdownMenuItem(text={Text("خروج از دستگاه")},enabled=!state.busy,onClick={menu=false;vm.logout()}) } }
                }
            }
            if(searching) OutlinedTextField(value=state.query, onValueChange=vm::search,
                modifier=Modifier.fillMaxWidth().padding(12.dp), singleLine=true, placeholder={Text("جستجو در پیام‌ها")})
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if(visible.isEmpty()) {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                        Text("◈", color=colors.primary, style=MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(12.dp))
                        Text(if(state.query.isNotBlank()) "پیامی پیدا نشد" else "فضای شخصی شما", fontWeight=FontWeight.Bold,
                            style=MaterialTheme.typography.titleLarge)
                        Text(if(state.query.isNotBlank()) "عبارت دیگری را امتحان کنید." else "پیام‌های خود را بین گوشی و کامپیوتر همگام کنید.",
                            textAlign=TextAlign.Center, color=colors.onSurfaceVariant, modifier=Modifier.padding(top=8.dp))
                    }
                } else LazyColumn(state=list, modifier=Modifier.fillMaxSize(), contentPadding=PaddingValues(12.dp),
                    verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(visible,key={_,m->m.id}) { index,m ->
                        val date=iranTime(m.timestamp,"EEEE d MMMM yyyy")
                        if(index==0 || iranTime(visible[index-1].timestamp,"EEEE d MMMM yyyy")!=date) {
                            Box(Modifier.fillMaxWidth().padding(vertical=6.dp),contentAlignment=Alignment.Center) {
                                Surface(shape=RoundedCornerShape(8.dp),color=colors.surface) {
                                    Text(date,Modifier.padding(horizontal=12.dp,vertical=5.dp),style=MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth(), contentAlignment=if(m.outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
                            val bubble=if(m.outgoing) if(dark) Color(0xFF005C4B) else Color(0xFFD9FDD3) else colors.surface
                            Surface(color=bubble,shape=RoundedCornerShape(14.dp),modifier=Modifier.widthIn(max=300.dp)) {
                                Column(Modifier.padding(horizontal=12.dp,vertical=8.dp)) {
                                    if(m.replyTo!=null) {
                                        val parent=state.messages.find{it.id==m.replyTo}
                                        Text(parent?.text ?: "پیام حذف شده",color=colors.onSurfaceVariant,
                                            style=MaterialTheme.typography.labelSmall,maxLines=2)
                                        HorizontalDivider(Modifier.padding(vertical=4.dp))
                                    }
                                    Text(m.text,style=MaterialTheme.typography.bodyLarge,color=colors.onSurface)
                                    Row(Modifier.align(Alignment.End),verticalAlignment=Alignment.CenterVertically) {
                                        Text(iranTime(m.timestamp,"HH:mm"),style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                                        if(m.outgoing) {
                                            Spacer(Modifier.width(6.dp))
                                            Text(if(m.status==MessageStatus.SENT) "✓" else "✓✓",
                                                color=if(m.status==MessageStatus.READ) Color(0xFF29A6D8) else colors.onSurfaceVariant)
                                        }
                                    }
                                    Row {
                                        TextButton(onClick={vm.reply(m.id)},contentPadding=PaddingValues(horizontal=7.dp)) {Text("پاسخ",style=MaterialTheme.typography.labelSmall)}
                                        TextButton(onClick={clipboard.setText(AnnotatedString(m.text))},contentPadding=PaddingValues(horizontal=7.dp)) {Text("کپی",style=MaterialTheme.typography.labelSmall)}
                                        if(m.outgoing) TextButton(onClick={pendingDelete=m.id},contentPadding=PaddingValues(horizontal=7.dp)) {Text("حذف",style=MaterialTheme.typography.labelSmall)}
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Surface(color=colors.surface,tonalElevation=2.dp) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    state.replyTo?.let { id ->
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("پاسخ به: "+(state.messages.find{it.id==id}?.text ?: ""),Modifier.weight(1f).padding(8.dp),maxLines=2)
                            TextButton(onClick={vm.reply(null)}){Text("لغو")}
                        }
                    }
                    Row(verticalAlignment=Alignment.Bottom) {
                        OutlinedTextField(value=state.draft,onValueChange=vm::draft,modifier=Modifier.weight(1f),
                            placeholder={Text("پیام خود را بنویسید…")},shape=RoundedCornerShape(24.dp),maxLines=5)
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick=vm::send,enabled=state.draft.isNotBlank() && !state.busy,modifier=Modifier.size(52.dp)) {
                            Text("➤",style=MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { id ->
        AlertDialog(onDismissRequest={pendingDelete=null},title={Text("حذف پیام")},text={Text("این پیام از گفتگو حذف شود؟")},
            confirmButton={TextButton(onClick={vm.delete(id);pendingDelete=null}){Text("حذف")}},
            dismissButton={TextButton(onClick={pendingDelete=null}){Text("انصراف")}})
    }
}
