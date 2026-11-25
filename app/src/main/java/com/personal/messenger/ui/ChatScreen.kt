package com.personal.messenger.ui

import android.icu.text.SimpleDateFormat
import android.icu.util.TimeZone
import android.icu.util.ULocale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.messenger.R
import com.personal.messenger.model.*
import com.personal.messenger.viewmodel.ChatViewModel
import java.util.Date

private fun iranTime(ms:Long,pattern:String):String =
    SimpleDateFormat(pattern,ULocale("fa_IR@calendar=persian")).apply{timeZone=TimeZone.getTimeZone("Asia/Tehran")}.format(Date(ms))

private val Green=Color(0xFF008069)
private val ReadBlue=Color(0xFF34B7F1)

@Composable
fun ChatScreen(state:ChatState,vm:ChatViewModel,dark:Boolean){
    val colors=MaterialTheme.colorScheme
    val header=if(dark)Color(0xFF1F2C34)else Color(0xFF075E54)
    val wallpaper=if(dark)Color(0xFF0B141A)else Color(0xFFEFEAE2)
    val composerColor=if(dark)Color(0xFF202C33)else Color.White
    val list=rememberLazyListState()
    val snackbar=remember{SnackbarHostState()}
    val clipboard=LocalClipboardManager.current
    var searching by remember{mutableStateOf(false)}
    var pendingDelete by remember{mutableStateOf<String?>(null)}
    var menu by remember{mutableStateOf(false)}
    var saveTarget by remember{mutableStateOf<ChatMessage?>(null)}
    var media by remember{mutableStateOf<Pair<String,MediaPreview>?>(null)}
    val currentMedia by rememberUpdatedState(media)
    var mounted by remember{mutableStateOf(true)}
    DisposableEffect(Unit){mounted=true;onDispose{mounted=false;currentMedia?.second?.file?.delete()}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let(vm::attach)}
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->uri?.let(vm::attach)}
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val target=saveTarget;saveTarget=null
        if(uri!=null&&target!=null)vm.saveMedia(target,uri)
    }
    val visible=remember(state.messages,state.query){state.filtered}
    LaunchedEffect(state.error){state.error?.let{snackbar.showSnackbar(it);vm.dismissError()}}
    LaunchedEffect(visible.lastOrNull()?.id){if(visible.isNotEmpty())list.animateScrollToItem(visible.lastIndex)}
    // Insets are consumed here, exactly once. Scaffold does not apply safeDrawing again.
    Column(Modifier.fillMaxSize().background(wallpaper).navigationBarsPadding().imePadding()){
        Surface(color=header){
            Column(Modifier.fillMaxWidth().statusBarsPadding()){
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
                    Image(painterResource(R.drawable.ic_launcher_foreground),contentDescription=null,
                        modifier=Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(alpha=.12f)).border(1.dp,Color.White.copy(alpha=.35f),CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)){
                        Text("گفتگوی شخصی",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=Color.White,maxLines=1)
                        Text(state.connection.ifBlank{"در حال اتصال"},style=MaterialTheme.typography.bodySmall,color=Color.White.copy(alpha=.78f),maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    IconButton(onClick={searching=!searching;if(!searching)vm.search("")}){Icon(if(searching)Icons.Default.Close else Icons.Default.Search,"جستجو",tint=Color.White)}
                    Box{
                        IconButton(onClick={menu=true}){Icon(Icons.Default.MoreVert,"تنظیمات",tint=Color.White)}
                        DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                            DropdownMenuItem(text={Text("همگام‌سازی")},leadingIcon={Icon(Icons.Default.Refresh,null)},onClick={menu=false;vm.refresh()})
                            DropdownMenuItem(text={Text(if(dark)"حالت روشن" else "حالت تاریک")},leadingIcon={Icon(if(dark)Icons.Default.LightMode else Icons.Default.DarkMode,null)},onClick={menu=false;vm.theme(dark)})
                            DropdownMenuItem(text={Text("خروج از دستگاه")},leadingIcon={Icon(Icons.Default.Logout,null)},enabled=!state.busy,onClick={menu=false;vm.logout()})
                        }
                    }
                }
                if(searching)Surface(shape=RoundedCornerShape(24.dp),color=composerColor,modifier=Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,bottom=10.dp)){
                    Row(Modifier.padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                        Icon(Icons.Default.Search,null,tint=colors.onSurfaceVariant,modifier=Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(value=state.query,onValueChange=vm::search,modifier=Modifier.weight(1f),singleLine=true,
                            textStyle=MaterialTheme.typography.bodyMedium.copy(color=colors.onSurface,textAlign=TextAlign.Start),cursorBrush=SolidColor(Green),
                            decorationBox={inner->Box{if(state.query.isEmpty())Text("جستجو در پیام‌ها",color=colors.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium);inner()}})
                        if(state.query.isNotEmpty())IconButton(onClick={vm.search("")},modifier=Modifier.size(32.dp)){Icon(Icons.Default.Close,"پاک کردن جستجو")}
                    }
                }
            }
        }
        Scaffold(modifier=Modifier.weight(1f).fillMaxWidth(),containerColor=wallpaper,contentWindowInsets=WindowInsets(0,0,0,0),
            snackbarHost={SnackbarHost(snackbar,Modifier.padding(horizontal=12.dp))},
            bottomBar={
                Column(Modifier.fillMaxWidth().background(wallpaper).padding(horizontal=7.dp,vertical=5.dp)){
                    state.replyTo?.let{id->
                        Surface(color=composerColor,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().padding(bottom=4.dp)){
                            Row(Modifier.padding(start=10.dp),verticalAlignment=Alignment.CenterVertically){
                                Box(Modifier.width(3.dp).height(36.dp).background(Green,RoundedCornerShape(2.dp)))
                                Column(Modifier.weight(1f).padding(horizontal=10.dp,vertical=6.dp)){
                                    Text("پاسخ به پیام",style=MaterialTheme.typography.labelMedium,color=Green,fontWeight=FontWeight.Bold)
                                    Text(state.messages.find{it.id==id}?.text.orEmpty(),style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                                }
                                IconButton(onClick={vm.reply(null)}){Icon(Icons.Default.Close,"لغو پاسخ",modifier=Modifier.size(20.dp))}
                            }
                        }
                    }
                    if(state.messages.any{it.status==MessageStatus.QUEUED})Text("پیام‌ها پس از اتصال ارسال می‌شوند.",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant,modifier=Modifier.padding(horizontal=10.dp))
                    state.progress?.let{p->LinearProgressIndicator(progress={p.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(vertical=4.dp),color=Green)}
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        Surface(shape=RoundedCornerShape(27.dp),color=composerColor,shadowElevation=1.dp,modifier=Modifier.weight(1f).heightIn(min=52.dp)){
                            Row(Modifier.fillMaxWidth().padding(start=14.dp,end=3.dp,top=3.dp,bottom=3.dp),verticalAlignment=Alignment.Bottom){
                                BasicTextField(value=state.draft,onValueChange=vm::draft,modifier=Modifier.weight(1f).heightIn(min=46.dp,max=112.dp),
                                    textStyle=MaterialTheme.typography.bodyLarge.copy(color=colors.onSurface,textAlign=TextAlign.Start),
                                    maxLines=4,cursorBrush=SolidColor(Green),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Default),
                                    decorationBox={inner->Box(Modifier.fillMaxWidth().padding(vertical=12.dp),contentAlignment=Alignment.CenterStart){
                                        if(state.draft.isEmpty())Text("پیام",color=colors.onSurfaceVariant,style=MaterialTheme.typography.bodyLarge);inner()
                                    }})
                                IconButton(enabled=!state.busy,onClick={picker.launch("*/*")},modifier=Modifier.size(42.dp)){Icon(Icons.Default.AttachFile,"پیوست فایل",tint=colors.onSurfaceVariant,modifier=Modifier.size(23.dp))}
                                IconButton(enabled=!state.busy,onClick={gallery.launch("image/*")},modifier=Modifier.size(42.dp)){Icon(Icons.Default.CameraAlt,"انتخاب عکس از گالری",tint=colors.onSurfaceVariant,modifier=Modifier.size(23.dp))}
                            }
                        }
                        VoiceRecorderButton(enabled=!state.busy,onRecorded=vm::sendVoice,onError=vm::report,hasText=state.draft.isNotBlank(),onSend=vm::send)
                    }
                }
            }){inner->
            Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)){
                ChatWallpaper(dark,Modifier.matchParentSize())
                LazyColumn(state=list,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=9.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                    if(visible.isEmpty())item{
                        Box(Modifier.fillParentMaxHeight().fillMaxWidth(),contentAlignment=Alignment.Center){
                            Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.padding(24.dp)){
                                Surface(shape=CircleShape,color=Green.copy(alpha=.12f)){Icon(Icons.Default.ChatBubbleOutline,null,tint=Green,modifier=Modifier.padding(20.dp).size(32.dp))}
                                Spacer(Modifier.height(10.dp))
                                Text(if(state.query.isNotBlank())"پیامی پیدا نشد" else "فضای شخصی شما",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                                Text(if(state.query.isNotBlank())"عبارت دیگری را امتحان کنید." else "پیام‌ها و فایل‌هایتان را بین دستگاه‌ها همگام کنید.",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant,textAlign=TextAlign.Center,modifier=Modifier.padding(top=6.dp))
                            }
                        }
                    }
                    itemsIndexed(visible,key={_,m->m.id}){index,m->
                        val day=iranTime(m.timestamp,"EEEE d MMMM yyyy")
                        if(index==0||iranTime(visible[index-1].timestamp,"EEEE d MMMM yyyy")!=day){
                            Box(Modifier.fillMaxWidth().padding(vertical=6.dp),contentAlignment=Alignment.Center){
                                Surface(shape=RoundedCornerShape(8.dp),color=if(dark)Color(0xFF18252D)else Color(0xFFFFFCF6),shadowElevation=1.dp){
                                    Text(day,Modifier.padding(horizontal=11.dp,vertical=5.dp),style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                                }
                            }
                        }
                        val activePreview=media?.takeIf{it.first==m.id}?.second
                        MessageBubble(m=m,parent=state.messages.find{it.id==m.replyTo},dark=dark,busy=state.busy,preview=activePreview,
                            onReply={vm.reply(m.id)},onCopy={clipboard.setText(AnnotatedString(m.text))},onDelete={pendingDelete=m.id},onRetry={vm.retry(m.id)},
                            onOpen={vm.openMedia(m){file,mime->
                                if(mounted){media?.second?.file?.delete();media=m.id to MediaPreview(file,mime,m.text)}else file.delete()
                            }},
                            onSave={saveTarget=m;saver.launch(m.text.replace('/','_').take(150).ifBlank{"attachment"})})
                    }
                }
            }
        }
    }
    pendingDelete?.let{id->
        AlertDialog(onDismissRequest={pendingDelete=null},title={Text("حذف پیام")},text={Text("این پیام از گفتگو حذف شود؟")},
            confirmButton={TextButton(onClick={vm.delete(id);pendingDelete=null}){Text("حذف")}},dismissButton={TextButton(onClick={pendingDelete=null}){Text("انصراف")}})
    }
}

@Composable
private fun ChatWallpaper(dark:Boolean,modifier:Modifier=Modifier){
    val tint=if(dark)Color.White.copy(alpha=.035f)else Color(0xFF827B6C).copy(alpha=.065f)
    Canvas(modifier){
        val step=56.dp.toPx()
        for(y in 0..(size.height/step).toInt())for(x in 0..(size.width/step).toInt()){
            val cx=x*step+(if(y%2==0)step*.25f else step*.65f);val cy=y*step+step*.45f
            drawCircle(tint,3.dp.toPx(),androidx.compose.ui.geometry.Offset(cx,cy),style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            drawLine(tint,androidx.compose.ui.geometry.Offset(cx+9.dp.toPx(),cy-5.dp.toPx()),androidx.compose.ui.geometry.Offset(cx+15.dp.toPx(),cy+1.dp.toPx()),1.dp.toPx())
        }
    }
}

@Composable
private fun MessageBubble(m:ChatMessage,parent:ChatMessage?,dark:Boolean,busy:Boolean,preview:MediaPreview?,
                          onReply:()->Unit,onCopy:()->Unit,onDelete:()->Unit,onRetry:()->Unit,onOpen:()->Unit,onSave:()->Unit){
    val colors=MaterialTheme.colorScheme
    val bubble=if(m.outgoing){if(dark)Color(0xFF005C4B)else Color(0xFFD9FDD3)}else{if(dark)Color(0xFF202C33)else Color.White}
    var actions by remember{mutableStateOf(false)}
    // Start is physically right in RTL; outgoing messages are on the right.
    Box(Modifier.fillMaxWidth(),contentAlignment=if(m.outgoing)Alignment.CenterStart else Alignment.CenterEnd){
        Surface(color=bubble,shape=RoundedCornerShape(topStart=if(m.outgoing)3.dp else 13.dp,topEnd=if(m.outgoing)13.dp else 3.dp,bottomStart=13.dp,bottomEnd=13.dp),
            shadowElevation=1.dp,modifier=Modifier.widthIn(max=300.dp)){
            Column(Modifier.padding(horizontal=8.dp,vertical=6.dp)){
                if(m.replyTo!=null){
                    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha=if(dark).15f else .045f)).padding(6.dp)){
                        Box(Modifier.width(3.dp).height(30.dp).background(if(dark)Color(0xFF53C8A5)else Green,RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(7.dp))
                        Column{Text("پاسخ به پیام",style=MaterialTheme.typography.labelSmall,color=if(dark)Color(0xFF7CE3C5)else Green,fontWeight=FontWeight.Bold)
                            Text(parent?.text?:"پیام حذف شده",style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)}
                    }
                    Spacer(Modifier.height(5.dp))
                }
                if(m.attachmentId!=null){
                    ChatAttachmentCard(m,preview,busy,onOpen,onSave)
                }else Text(m.text,style=MaterialTheme.typography.bodyLarge.copy(lineHeight=22.sp),color=colors.onSurface,modifier=Modifier.padding(horizontal=3.dp))
                Row(Modifier.align(Alignment.End),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    Text(iranTime(m.timestamp,"HH:mm"),style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                    if(m.outgoing){
                        val icon=when(m.status){MessageStatus.QUEUED->Icons.Default.Schedule;MessageStatus.FAILED->Icons.Default.ErrorOutline;MessageStatus.SENT->Icons.Default.Done;else->Icons.Default.DoneAll}
                        Icon(icon,when(m.status){MessageStatus.QUEUED->"در صف ارسال";MessageStatus.FAILED->"ناموفق";MessageStatus.SENT->"ارسال شده";MessageStatus.DELIVERED->"تحویل داده شده";MessageStatus.READ->"خوانده شده"},modifier=Modifier.size(17.dp),tint=if(m.status==MessageStatus.READ)ReadBlue else if(m.status==MessageStatus.FAILED)colors.error else colors.onSurfaceVariant)
                    }
                    Box{
                        IconButton(onClick={actions=true},modifier=Modifier.size(30.dp)){Icon(Icons.Default.MoreHoriz,"عملیات پیام",modifier=Modifier.size(18.dp),tint=colors.onSurfaceVariant)}
                        DropdownMenu(expanded=actions,onDismissRequest={actions=false}){
                            if(m.status!=MessageStatus.QUEUED&&m.status!=MessageStatus.FAILED)DropdownMenuItem(text={Text("پاسخ")},leadingIcon={Icon(Icons.Default.Reply,null)},onClick={actions=false;onReply()})
                            DropdownMenuItem(text={Text("کپی")},leadingIcon={Icon(Icons.Default.ContentCopy,null)},onClick={actions=false;onCopy()})
                            if(m.status==MessageStatus.FAILED)DropdownMenuItem(text={Text("تلاش مجدد")},leadingIcon={Icon(Icons.Default.Refresh,null)},onClick={actions=false;onRetry()})
                            if(m.outgoing)DropdownMenuItem(text={Text("حذف")},leadingIcon={Icon(Icons.Default.DeleteOutline,null)},onClick={actions=false;onDelete()})
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatAttachmentCard(m:ChatMessage,preview:MediaPreview?,busy:Boolean,onOpen:()->Unit,onSave:()->Unit){
    val colors=MaterialTheme.colorScheme
    val panel=colors.onSurface.copy(alpha=.055f)
    Column(Modifier.widthIn(min=180.dp,max=270.dp)){
        when(m.kind){
            "image"->{
                if(preview!=null)ImageMessagePreview(preview,iranTime(m.timestamp,"HH:mm"))
                else Surface(color=panel,shape=RoundedCornerShape(9.dp),modifier=Modifier.fillMaxWidth().height(112.dp).clickable(enabled=!busy,onClick=onOpen)){
                    Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                        Icon(Icons.Default.Image,null,tint=colors.onSurfaceVariant,modifier=Modifier.size(32.dp))
                        Text("نمایش تصویر",style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)
                    }
                }
            }
            "voice"->{
                if(preview!=null)AudioMessagePlayer(preview)
                else Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(panel).clickable(enabled=!busy,onClick=onOpen).padding(8.dp),verticalAlignment=Alignment.CenterVertically){
                    Icon(Icons.Default.PlayCircleFilled,"پخش پیام صوتی",tint=Green,modifier=Modifier.size(42.dp))
                    Column(Modifier.weight(1f).padding(horizontal=8.dp)){
                        VoiceWave(0f)
                        Text("پیام صوتی · برای پخش لمس کنید",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                    }
                    Icon(Icons.Default.Mic,null,tint=Green,modifier=Modifier.size(20.dp))
                }
            }
            else->Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(panel).padding(9.dp),verticalAlignment=Alignment.CenterVertically){
                Surface(shape=RoundedCornerShape(9.dp),color=Green.copy(alpha=.15f)){Icon(Icons.Default.InsertDriveFile,null,tint=Green,modifier=Modifier.padding(10.dp).size(25.dp))}
                Column(Modifier.weight(1f).padding(horizontal=9.dp)){
                    Text(m.text,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(m.text.substringAfterLast('.', "فایل").uppercase().take(12),style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                }
                IconButton(enabled=!busy,onClick=onSave){Icon(Icons.Default.Download,"ذخیره فایل",tint=Green)}
            }
        }
        if(m.kind!="file")Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text(m.text,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=colors.onSurfaceVariant)
                if(preview!=null)Text(formatMediaSize(preview.file.length()),style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
            }
            IconButton(enabled=!busy,onClick=onSave,modifier=Modifier.size(38.dp)){Icon(Icons.Default.Download,"ذخیره فایل",modifier=Modifier.size(20.dp),tint=Green)}
        }
    }
}
