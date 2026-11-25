package com.personal.messenger.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.messenger.data.*
import com.personal.messenger.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()
    private val api = MessengerApi()
    private val store = SecureSessionStore(app)
    private val lock = SyncGate.lock
    private val cache = LocalChatStore(app)
    private var failures=0
    private var idle=0
    private val prefs=app.getSharedPreferences("appearance",0)
    private var session: Session? = null
    private var cursor = 0L
    private var poll: Job? = null
    private var active = false
    private suspend fun loadCache(s:Session){
        val snapshot=withContext(Dispatchers.IO){cache.prepare(s.id);Pair(cache.cursor(),cache.messages())}
        cursor=snapshot.first;mutable.update{it.copy(messages=snapshot.second)}
    }

    init {
        viewModelScope.launch {
            lock.withLock {
                session = withContext(Dispatchers.IO) { store.read() }
                try{val s=session;if(s!=null){loadCache(s);BackgroundSchedule.start(getApplication<Application>())}else withContext(Dispatchers.IO){cache.clear()}}
                catch(e:Exception){if(e is CancellationException)throw e;report("حافظه محلی قابل خواندن نیست.")}
                mutable.update { it.copy(initializing=false, deviceName=session?.name, connection=if(session==null) "" else "در حال اتصال",dark=if(prefs.contains("dark"))prefs.getBoolean("dark",false)else null) }
            }
            startPoll()
        }
    }
    fun foreground(on:Boolean){
        active=on;SyncGate.foreground=on
        if(on){(getApplication<Application>().getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(101);startPoll()}
        else{poll?.cancel();poll=null;if(session!=null)BackgroundSchedule.send(getApplication<Application>())}
    }
    private fun startPoll() {
        if(!active || session==null || poll?.isActive==true) return
        poll=viewModelScope.launch {
            while(isActive&&session!=null) {
                lock.withLock { syncLocked() }
                delay(RetryPolicy.delayMs(failures,idle))
            }
        }
    }
    fun login(name: String, secret: String) {
        if(mutable.value.busy || mutable.value.initializing || name.isBlank() || secret.isBlank()) return
        mutable.update { it.copy(busy=true,error=null) }
        viewModelScope.launch {
            lock.withLock {
                try {
                    val s=api.enroll(name.trim(),secret.trim())
                    try { withContext(Dispatchers.IO) { store.save(s) } } catch(e: Exception) {
                        if(e is CancellationException) throw e
                        runCatching { api.revoke(s) }; throw e
                    }
                    session=s;cursor=0;failures=0;idle=0
                    withContext(Dispatchers.IO){cache.prepare(s.id)}
                    BackgroundSchedule.start(getApplication<Application>())
                    mutable.update { it.copy(deviceName=s.name,connection="در حال همگام‌سازی",messages=emptyList(),error=null) }
                } catch(e: Exception) {
                    if(e is CancellationException) throw e
                    mutable.update { it.copy(error=if(e is ApiException && e.status==401) "کلید ثبت دستگاه معتبر نیست." else error(e)) }
                } finally { mutable.update { it.copy(busy=false) } }
            }
            startPoll()
        }
    }
    fun logout() {
        if(mutable.value.busy) return
        mutable.update { it.copy(busy=true,error=null) }
        viewModelScope.launch {
            lock.withLock {
                try {
                    session?.let { s -> try { api.revoke(s) } catch(e: ApiException) { if(e.status!=401) throw e } }
                    clearLocked(null)
                } catch(e: Exception) {
                    if(e is CancellationException) throw e
                    mutable.update { it.copy(error="خروج انجام نشد؛ اتصال را بررسی کنید.") }
                } finally { mutable.update { it.copy(busy=false) } }
            }
        }
    }
    private suspend fun clearLocked(message: String?) {
        withContext(Dispatchers.IO) { store.clear();cache.clear() }
        BackgroundSchedule.stop(getApplication<Application>())
        session=null;cursor=0;failures=0;idle=0
        mutable.update { ChatState(dark=it.dark,initializing=false,error=message) }
    }
    private fun error(e: Exception): String = when {
        e is ApiException && e.status==409 -> "تعارض در ثبت پیام؛ دوباره تلاش کنید."
        e is ApiException && e.status==403 -> "این عملیات مجاز نیست."
        else -> "ارتباط با سرور برقرار نشد؛ دوباره تلاش کنید."
    }
    private suspend fun handle(e: Exception) {
        if(e is CancellationException) throw e
        if(e is ApiException && e.status==401) clearLocked("نشست معتبر نیست. دوباره وارد شوید.")
        else { failures=(failures+1).coerceAtMost(6);mutable.update { it.copy(connection="آفلاین · پیام‌ها محفوظ‌اند") } }
    }
    private suspend fun syncLocked() {
        val s=session ?: return
        try {
            // A background worker may have confirmed queued messages before this activity resumed.
            val queued=withContext(Dispatchers.IO){cache.queued()}
            if(queued.isNotEmpty())idle=0
            for(m in queued){
                try{upsert(api.send(s,m.id,m.text,m.replyTo))}
                catch(e:ApiException){
                    if(RetryPolicy.permanent(e.status))upsert(m.copy(status=MessageStatus.FAILED))else throw e
                }
            }
            var more=true;var rounds=0;var changed=false
            while(more&&rounds++<8){
                val page=api.sync(s,cursor)
                if(page.events.isNotEmpty()||page.cursor!=cursor){
                    withContext(Dispatchers.IO){cache.applyPage(page)}
                    cursor=page.cursor;changed=true
                }
                more=page.hasMore
            }
            if(changed||idle==0)loadCache(s)
            if(active&&!more){
                withContext(Dispatchers.IO){cache.markNotice(cursor)}
                mutable.value.messages.filter{!it.outgoing&&it.status!=MessageStatus.READ}.take(15).forEach{m->
                    try{upsert(api.receipt(s,m.id))}
                    catch(e:ApiException){if(e.status!=404&&e.status!=410)throw e}
                }
            }
            failures=0;idle=if(changed)0 else (idle+1).coerceAtMost(20)
            mutable.update{it.copy(connection=if(more)"در حال دریافت تاریخچه" else "متصل · همگام‌سازی فعال")}
        }catch(e:Exception){handle(e)}
    }
    fun refresh(){viewModelScope.launch{lock.withLock{idle=0;syncLocked()}}}
    fun dismissError()=mutable.update{it.copy(error=null)}
    fun draft(value:String)=mutable.update{it.copy(draft=value.take(16000))}
    fun search(value:String)=mutable.update{it.copy(query=value)}
    fun theme(currentDark:Boolean){prefs.edit().putBoolean("dark",!currentDark).apply();mutable.update{it.copy(dark=!currentDark)}}
    fun reply(id:String?)=mutable.update{it.copy(replyTo=id)}
    private suspend fun upsert(m:ChatMessage){
        withContext(Dispatchers.IO){cache.put(m)}
        mutable.update{s->s.copy(messages=(s.messages.filterNot{it.id==m.id}+m).sortedWith(compareBy<ChatMessage>{it.timestamp}.thenBy{it.id}))}
    }
    fun send(){
        val s=mutable.value
        if(s.busy||s.draft.isBlank()||session==null)return
        val m=ChatMessage(text=s.draft,replyTo=s.replyTo,status=MessageStatus.QUEUED)
        mutable.update{it.copy(busy=true,error=null)}
        viewModelScope.launch{
            lock.withLock{
                try{
                    if(session==null)return@withLock
                    upsert(m)
                    mutable.update{it.copy(draft=if(it.draft==m.text)"" else it.draft,replyTo=if(it.replyTo==m.replyTo)null else it.replyTo,busy=false)}
                    idle=0
                    BackgroundSchedule.send(getApplication<Application>())
                    syncLocked()
                }catch(e:Exception){if(e is CancellationException)throw e;report("ذخیره پیام انجام نشد.")}
                finally{mutable.update{it.copy(busy=false)}}
            }
        }
    }
    fun retry(id:String){
        if(mutable.value.busy)return
        viewModelScope.launch{lock.withLock{
            val m=mutable.value.messages.find{it.id==id&&it.status==MessageStatus.FAILED}?:return@withLock
            try{upsert(m.copy(status=MessageStatus.QUEUED));idle=0;syncLocked()}
            catch(e:Exception){if(e is CancellationException)throw e;report("تلاش مجدد انجام نشد.")}
        }}
    }
    fun report(message:String) = mutable.update { it.copy(error=message) }
    private fun progress(p:Float?) = mutable.update { it.copy(progress=p) }
    fun attach(uri:Uri) {
        if(mutable.value.busy||session==null)return
        mutable.update{it.copy(busy=true,error=null,progress=0f)}
        val reply=mutable.value.replyTo
        viewModelScope.launch {
            lock.withLock {
                var file:File?=null
                try{
                    val s=session?:return@withLock
                    val ctx=getApplication<Application>();val resolver=ctx.contentResolver
                    val info=withContext(Dispatchers.IO){
                        val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())c.getString(0) else null} ?: "attachment"
                        val mime=resolver.getType(uri)?.substringBefore(';') ?: "application/octet-stream"
                        val f=File.createTempFile("upload-",".bin",ctx.cacheDir);file=f
                        resolver.openInputStream(uri)?.use{input->f.outputStream().use{out->
                            val b=ByteArray(65536);var total=0L
                            while(true){val n=input.read(b);if(n<0)break;total+=n;if(total>20971520)throw ApiException(413,"BODY_TOO_LARGE");out.write(b,0,n)}
                        }}?:throw ApiException(400,"EMPTY_FILE")
                        Triple(f,name.take(240),mime)
                    }
                    val kind=when{info.third.startsWith("image/")->"image";info.third.startsWith("audio/")->"voice";else->"file"}
                    sendAttachment(s,info.first,info.second,info.third,kind,null,reply)
                }catch(e:Exception){mediaError(e)}finally{file?.delete();mutable.update{it.copy(busy=false,progress=null)}}
            }
        }
    }
    fun sendVoice(file:File,duration:Long){
        if(mutable.value.busy||session==null){file.delete();return}
        mutable.update{it.copy(busy=true,error=null,progress=0f)}
        val reply=mutable.value.replyTo
        viewModelScope.launch{lock.withLock{
            try{val s=session?:return@withLock;sendAttachment(s,file,"voice-"+System.currentTimeMillis()+".m4a","audio/mp4","voice",duration,reply)}
            catch(e:Exception){mediaError(e)}finally{file.delete();mutable.update{it.copy(busy=false,progress=null)}}
        }}
    }
    private suspend fun sendAttachment(s:Session,file:File,name:String,mime:String,kind:String,duration:Long?,reply:String?){
        if(file.length() !in 1..20971520)throw ApiException(413,"BODY_TOO_LARGE")
        val safeName=name.replace(Regex("[\\x00-\\x1f\\x7f]"),"_").ifBlank{"attachment"}
        val attachment=api.upload(s,file,safeName,mime,duration,::progress)
        val id=UUID.randomUUID().toString()
        upsert(api.sendMedia(s,id,kind,safeName,attachment,reply))
        mutable.update{it.copy(replyTo=if(it.replyTo==reply)null else it.replyTo)}
    }
    private suspend fun mediaError(e:Exception){
        if(e is CancellationException)throw e
        when{
            e is ApiException && e.status==401 -> clearLocked("نشست معتبر نیست.")
            e is ApiException && e.status==413 -> report("حجم فایل باید بین ۱ بایت تا ۲۰ مگابایت باشد.")
            e is ApiException && e.status==507 -> report("ظرفیت ذخیره‌سازی رسانه پر شده است.")
            e is ApiException && e.code=="MEDIA_PENDING" -> report("فایل هنوز همگام نشده است؛ کمی بعد دوباره امتحان کنید.")
            else -> report("ارسال یا دریافت فایل انجام نشد.")
        }
    }
    fun openMedia(m:ChatMessage,done:(File,String)->Unit){
        val id=m.attachmentId?:return
        if(mutable.value.busy||session==null)return
        mutable.update{it.copy(busy=true,error=null,progress=0f)}
        viewModelScope.launch{lock.withLock{
            var f:File?=null
            try{
                val s=session?:return@withLock
                f=File.createTempFile("media-",".bin",getApplication<Application>().cacheDir)
                val mime=api.download(s,id,f,::progress)
                done(f,mime);f=null
            }catch(e:Exception){mediaError(e)}finally{f?.delete();mutable.update{it.copy(busy=false,progress=null)}}
        }}
    }
    fun saveMedia(m:ChatMessage,uri:Uri){
        openMedia(m){file,_->
            viewModelScope.launch{
                try{withContext(Dispatchers.IO){
                    getApplication<Application>().contentResolver.openOutputStream(uri,"w")?.use{out->file.inputStream().use{it.copyTo(out)}}?:throw java.io.IOException("output")
                };report("فایل ذخیره شد.")}
                catch(e:Exception){if(e is CancellationException)throw e;report("ذخیره فایل انجام نشد.")}
                finally{file.delete()}
            }
        }
    }
    fun delete(id: String) {
        if(mutable.value.busy) return
        mutable.update { it.copy(busy=true,error=null) }
        viewModelScope.launch {
            lock.withLock {
                try {
                    val s=session ?: return@withLock
                    val local=mutable.value.messages.find{it.id==id}
                    if(local?.status==MessageStatus.QUEUED||local?.status==MessageStatus.FAILED){
                        // A previous attempt may have reached the server; try deletion before dropping local state.
                        try{api.delete(s,id)}catch(e:ApiException){if(e.status!=404)throw e}
                    }else api.delete(s,id)
                    withContext(Dispatchers.IO){cache.remove(id)}
                    mutable.update { it.copy(messages=it.messages.filterNot { m->m.id==id },replyTo=it.replyTo.takeUnless { r->r==id }) }
                } catch(e: Exception) { handle(e) }
                finally { mutable.update { it.copy(busy=false) } }
            }
        }
    }
}
