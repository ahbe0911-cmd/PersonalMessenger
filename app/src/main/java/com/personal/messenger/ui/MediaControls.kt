package com.personal.messenger.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.Crossfade
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

data class MediaPreview(val file:File,val mime:String,val name:String)

@Composable
fun VoiceRecorderButton(enabled:Boolean,onRecorded:(File,Long)->Unit,onError:(String)->Unit,hasText:Boolean=false,onSend:()->Unit={}){
    val ctx=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var recorder by remember{mutableStateOf<MediaRecorder?>(null)}
    var file by remember{mutableStateOf<File?>(null)}
    var started by remember{mutableLongStateOf(0L)}
    fun stop(send:Boolean){
        val r=recorder?:return
        recorder=null
        val f=file;file=null
        val duration=(System.currentTimeMillis()-started).coerceAtLeast(1L)
        val ok=runCatching{r.stop()}.isSuccess
        runCatching{r.release()}
        if(send&&ok&&f!=null&&f.length()>0)onRecorded(f,duration)
        else {f?.delete();if(send)onError("ضبط صدا ناموفق بود.")}
    }
    fun start(){
        if(!enabled||recorder!=null)return
        var r:MediaRecorder?=null;var f:File?=null
        try{
            f=File.createTempFile("voice-",".m4a",ctx.cacheDir)
            r=if(Build.VERSION.SDK_INT>=31)MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC);r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);r.setAudioEncodingBitRate(64000);r.setAudioSamplingRate(44100)
            r.setOutputFile(f.absolutePath);r.prepare();r.start()
            file=f;started=System.currentTimeMillis();recorder=r
        }catch(e:Exception){runCatching{r?.release()};f?.delete();onError("میکروفن در دسترس نیست.")}
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){if(it)start()else onError("مجوز میکروفن داده نشد.")}
    DisposableEffect(owner){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)stop(false)}
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);stop(false)}
    }
    LaunchedEffect(recorder){if(recorder!=null){delay(300000);stop(true)}}
    FilledIconButton(enabled=enabled||recorder!=null,onClick={
        if(recorder!=null)stop(true)
        else if(hasText)onSend()
        else if(ContextCompat.checkSelfPermission(ctx,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    },modifier=Modifier.size(52.dp),shape=CircleShape,colors=IconButtonDefaults.filledIconButtonColors(
        containerColor=if(recorder!=null)Color(0xFFD74747)else Color(0xFF008069),contentColor=Color.White)){
        Crossfade(targetState=if(recorder!=null)2 else if(hasText)1 else 0,label="composer-action"){mode->
            Icon(when(mode){2->Icons.Default.Stop;1->Icons.Default.Send;else->Icons.Default.Mic},
                when(mode){2->"پایان ضبط و ارسال";1->"ارسال پیام";else->"ضبط پیام صوتی"},modifier=Modifier.size(25.dp))
        }
    }
}

@Composable
fun MediaPreviewDialog(preview:MediaPreview,onClose:()->Unit){
    var player by remember(preview.file){mutableStateOf<MediaPlayer?>(null)}
    var playing by remember(preview.file){mutableStateOf(false)}
    var error by remember(preview.file){mutableStateOf(false)}
    val bitmap by produceState<android.graphics.Bitmap?>(null,preview.file){
        if(preview.mime.startsWith("image/"))value=withContext(Dispatchers.IO){
            runCatching{
                val o=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                BitmapFactory.decodeFile(preview.file.path,o)
                var sample=1;while(o.outWidth/sample>1800||o.outHeight/sample>1800)sample*=2
                BitmapFactory.decodeFile(preview.file.path,BitmapFactory.Options().apply{inSampleSize=sample})
            }.getOrNull()
        }
    }
    DisposableEffect(preview.file){onDispose{runCatching{player?.release()};preview.file.delete()}}
    AlertDialog(onDismissRequest=onClose,title={Text(preview.name)},text={
        Column{
            if(preview.mime.startsWith("image/")){
                if(bitmap!=null)Image(bitmap!!.asImageBitmap(),contentDescription=preview.name,modifier=Modifier.fillMaxWidth().heightIn(max=380.dp),contentScale=ContentScale.Fit)
                else Text("در حال آماده‌سازی تصویر؛ در صورت نمایش‌ندادن، فایل را ذخیره کنید.")
            }else if(preview.mime.startsWith("audio/")){
                Button(onClick={
                    try{
                        val existing=player
                        if(existing!=null&&playing){existing.pause();playing=false}
                        else if(existing!=null){existing.start();playing=true}
                        else{val p=MediaPlayer();player=p;p.setDataSource(preview.file.absolutePath);p.setOnPreparedListener{it.start();playing=true};p.setOnCompletionListener{playing=false};p.setOnErrorListener{_,_,_->error=true;playing=false;true};p.prepareAsync()}
                    }catch(e:Exception){error=true;playing=false}
                }){Text(if(playing)"توقف" else "پخش")}
                if(error)Text("این قالب صوتی قابل پخش نیست؛ فایل را ذخیره کنید.")
            }else Text("برای باز کردن این نوع فایل، آن را ذخیره کنید.")
        }
    },confirmButton={TextButton(onClick=onClose){Text("بستن")}})
}


fun formatMediaSize(bytes:Long):String=if(bytes>=1048576)String.format(java.util.Locale("fa"),"%.1f MB",bytes/1048576.0)else String.format(java.util.Locale("fa"),"%.0f KB",bytes/1024.0)

@Composable
fun ImageMessagePreview(preview:MediaPreview,time:String){
    val bitmap by produceState<android.graphics.Bitmap?>(null,preview.file){
        value=withContext(Dispatchers.IO){runCatching{
            val o=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(preview.file.path,o)
            var sample=1;while(o.outWidth/sample>1500||o.outHeight/sample>1500)sample*=2
            BitmapFactory.decodeFile(preview.file.path,BitmapFactory.Options().apply{inSampleSize=sample})
        }.getOrNull()}
    }
    Box(Modifier.fillMaxWidth().heightIn(min=100.dp,max=240.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center){
        val b=bitmap
        if(b!=null){
            Image(b.asImageBitmap(),preview.name,Modifier.fillMaxWidth().heightIn(max=240.dp),contentScale=ContentScale.Fit)
            Text(time,Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha=.55f),RoundedCornerShape(5.dp)).padding(horizontal=6.dp,vertical=3.dp),color=Color.White,style=MaterialTheme.typography.labelSmall)
        }else Text("آماده‌سازی تصویر…",modifier=Modifier.padding(12.dp),style=MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun VoiceWave(progress:Float){
    val base=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.32f)
    val active=Color(0xFF008069)
    val heights=remember{listOf(.24f,.55f,.38f,.88f,.48f,.74f,.95f,.35f,.62f,.82f,.45f,.9f,.58f,.32f,.72f,.94f,.57f,.76f,.35f,.56f,.84f,.48f,.64f,.26f)}
    Canvas(Modifier.fillMaxWidth().height(22.dp)){
        val step=size.width/heights.size
        heights.forEachIndexed{i,h->val x=(i+.5f)*step;val y=size.height*h/2;drawLine(if(i.toFloat()/heights.size<progress)active else base,Offset(x,size.height/2-y),Offset(x,size.height/2+y),strokeWidth=(step*.48f).coerceAtLeast(1f),cap=androidx.compose.ui.graphics.StrokeCap.Round)}
    }
}

@Composable
fun AudioMessagePlayer(preview:MediaPreview){
    var player by remember(preview.file){mutableStateOf<MediaPlayer?>(null)}
    var playing by remember(preview.file){mutableStateOf(false)}
    var ready by remember(preview.file){mutableStateOf(false)}
    var error by remember(preview.file){mutableStateOf(false)}
    var position by remember(preview.file){mutableIntStateOf(0)}
    var duration by remember(preview.file){mutableIntStateOf(0)}
    DisposableEffect(preview.file){onDispose{runCatching{player?.release()}}}
    LaunchedEffect(playing,ready){while(ready&&playing){position=runCatching{player?.currentPosition?:0}.getOrDefault(0);delay(250)}}
    Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
        FilledIconButton(onClick={
            try{
                val existing=player
                if(existing!=null&&playing){existing.pause();playing=false}
                else if(existing!=null&&ready){existing.start();playing=true}
                else if(existing==null){
                    val p=MediaPlayer();player=p;p.setDataSource(preview.file.absolutePath)
                    p.setOnPreparedListener{duration=it.duration;ready=true;it.start();playing=true}
                    p.setOnCompletionListener{playing=false;position=duration}
                    p.setOnErrorListener{_,_,_->error=true;playing=false;true};p.prepareAsync()
                }
            }catch(e:Exception){error=true;playing=false}
        },colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xFF008069),contentColor=Color.White),modifier=Modifier.size(42.dp)){
            Icon(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,if(playing)"مکث" else "پخش")
        }
        Column(Modifier.weight(1f).padding(horizontal=9.dp)){
            VoiceWave(if(duration>0)position.toFloat()/duration else 0f)
            LinearProgressIndicator(progress={if(duration>0)(position.toFloat()/duration).coerceIn(0f,1f) else 0f},modifier=Modifier.fillMaxWidth().height(3.dp),color=Color(0xFF008069))
            Text(if(error)"قالب صوتی پشتیبانی نمی‌شود" else String.format(java.util.Locale("fa"),"%02d:%02d / %02d:%02d",position/60000,position/1000%60,duration/60000,duration/1000%60),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.Mic,null,tint=Color(0xFF008069),modifier=Modifier.size(22.dp))
    }
}
