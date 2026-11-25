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

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()
    private val api = MessengerApi()
    private val store = SecureSessionStore(app)
    private val lock = Mutex()
    private var session: Session? = null
    private var cursor = 0L
    private var poll: Job? = null
    private var active = false
    private var pending: Pending? = null
    private data class Pending(val id: String, val text: String, val reply: String?)

    init {
        viewModelScope.launch {
            lock.withLock {
                session = withContext(Dispatchers.IO) { store.read() }
                mutable.update { it.copy(initializing=false, deviceName=session?.name, connection=if(session==null) "" else "در حال اتصال") }
            }
            startPoll()
        }
    }
    fun foreground(on: Boolean) { active=on; if(on) startPoll() else { poll?.cancel(); poll=null } }
    private fun startPoll() {
        if(!active || session==null || poll?.isActive==true) return
        poll=viewModelScope.launch {
            while(isActive) {
                lock.withLock { syncLocked() }
                delay(3000)
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
                    session=s;cursor=0;pending=null
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
        withContext(Dispatchers.IO) { store.clear() }
        session=null;cursor=0;pending=null
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
        else mutable.update { it.copy(connection="ارتباط قطع است",error=error(e)) }
    }
    private suspend fun syncLocked() {
        val s=session ?: return
        try {
            val map=mutable.value.messages.associateBy { it.id }.toMutableMap()
            var more=true;var rounds=0
            while(more && rounds++<8) {
                val page=api.sync(s,cursor)
                page.events.forEach { event ->
                    val message=event.message
                    if(message==null) map.remove(event.id) else map[event.id]=message
                }
                cursor=page.cursor;more=page.hasMore
                mutable.update { it.copy(messages=map.values.sortedWith(compareBy<ChatMessage>{m->m.timestamp}.thenBy{m->m.id}),
                    connection=if(more) "در حال دریافت تاریخچه" else "متصل · همگام‌سازی فعال") }
            }
            if(active) {
                map.values.filter { !it.outgoing && it.status!=MessageStatus.READ }.take(15).forEach { m ->
                    try { upsert(api.receipt(s,m.id)) }
                    catch(e: ApiException) { if(e.status!=404 && e.status!=410) throw e }
                }
            }
        } catch(e: Exception) { handle(e) }
    }
    fun refresh() { viewModelScope.launch { lock.withLock { syncLocked() } } }
    fun dismissError() = mutable.update { it.copy(error=null) }
    fun draft(value: String) = mutable.update { it.copy(draft=value.take(16000)) }
    fun search(value: String) = mutable.update { it.copy(query=value) }
    fun theme(currentDark: Boolean) = mutable.update { it.copy(dark=!currentDark) }
    fun reply(id: String?) = mutable.update { it.copy(replyTo=id) }
    private fun upsert(m: ChatMessage) = mutable.update { s ->
        s.copy(messages=(s.messages.filterNot { it.id==m.id }+m).sortedWith(compareBy<ChatMessage>{it.timestamp}.thenBy{it.id}))
    }
    fun send() {
        val s=mutable.value
        if(s.busy || s.draft.isBlank() || session==null) return
        val p=pending?.takeIf { it.text==s.draft && it.reply==s.replyTo } ?: Pending(UUID.randomUUID().toString(),s.draft,s.replyTo)
        pending=p;mutable.update { it.copy(busy=true,error=null) }
        viewModelScope.launch {
            lock.withLock {
                try {
                    val current=session ?: return@withLock
                    upsert(api.send(current,p.id,p.text,p.reply))
                    pending=null
                    mutable.update { it.copy(draft=if(it.draft==p.text) "" else it.draft,replyTo=if(it.replyTo==p.reply) null else it.replyTo,connection="متصل · همگام‌سازی فعال") }
                } catch(e: Exception) { handle(e) }
                finally { mutable.update { it.copy(busy=false) } }
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
                    api.delete(s,id)
                    mutable.update { it.copy(messages=it.messages.filterNot { m->m.id==id },replyTo=it.replyTo.takeUnless { r->r==id }) }
                } catch(e: Exception) { handle(e) }
                finally { mutable.update { it.copy(busy=false) } }
            }
        }
    }
}
