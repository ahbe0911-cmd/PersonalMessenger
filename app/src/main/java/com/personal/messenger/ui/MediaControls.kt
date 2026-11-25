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
fun VoiceRecorderButton(enabled:Boolean,onRecorded:(File,Long)->Unit,onError:(String)->Unit){
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
    TextButton(enabled=enabled||recorder!=null,onClick={
        if(recorder!=null)stop(true)
        else if(ContextCompat.checkSelfPermission(ctx,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }){Text(if(recorder!=null)"■ پایان و ارسال" else "● ضبط صدا",color=if(recorder!=null)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)}
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
