package com.personal.messenger.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.personal.messenger.MainActivity
import com.personal.messenger.R
import com.personal.messenger.model.MessageStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object SyncGate {
    val lock=Mutex()
    @Volatile var foreground=false
}
object RetryPolicy {
    fun permanent(status:Int)=status in 400..499 && status !in setOf(401,408,425,429)
    fun delayMs(failures:Int,idle:Int):Long = if(failures>0) (3000L*(1L shl failures.coerceIn(1,5))).coerceAtMost(90000L)
        else if(idle>=5)15000L else 3000L
}
object BackgroundSchedule {
    private const val PERIODIC="personal-messenger-periodic"
    private const val SEND="personal-messenger-send"
    private val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun start(ctx:Context){
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackgroundSyncWorker>(15,TimeUnit.MINUTES).setConstraints(constraints).build())
    }
    fun send(ctx:Context){
        WorkManager.getInstance(ctx).enqueueUniqueWork(SEND,ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackgroundSyncWorker>().setConstraints(constraints).build())
    }
    fun stop(ctx:Context){
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC);WorkManager.getInstance(ctx).cancelUniqueWork(SEND)
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(101)
    }
}
class BackgroundSyncWorker(ctx:Context,params:WorkerParameters):CoroutineWorker(ctx,params){
    override suspend fun doWork():Result=withContext(Dispatchers.IO){
        SyncGate.lock.withLock{
            if(SyncGate.foreground)return@withLock Result.success()
            val ctx=applicationContext;val secure=SecureSessionStore(ctx);val session=secure.read()?:return@withLock Result.success()
            val db=LocalChatStore(ctx);val api=MessengerApi()
            try{
                db.prepare(session.id)
                for(m in db.queued()){
                    if(SyncGate.foreground)break
                    try{db.put(api.send(session,m.id,m.text,m.replyTo))}
                    catch(e:ApiException){if(RetryPolicy.permanent(e.status))db.put(m.copy(status=MessageStatus.FAILED))else throw e}
                }
                if(SyncGate.foreground)return@withLock Result.success()
                val previous=db.noticeCursor()
                var cursor=previous?:db.cursor();val incoming=mutableSetOf<String>();var more=true;var rounds=0
                while(more&&rounds++<5&&!SyncGate.foreground){
                    val page=api.sync(session,cursor)
                    page.events.forEach{e->val m=e.message;if(m==null)incoming.remove(e.id)else if(!m.outgoing&&m.status!=MessageStatus.READ)incoming.add(e.id)else incoming.remove(e.id)}
                    cursor=page.cursor;more=page.hasMore
                }
                if(!SyncGate.foreground){
                    if(previous!=null&&incoming.isNotEmpty())notifyNew(ctx,incoming.size)
                    db.markNotice(cursor)
                }
                Result.success()
            }catch(e:CancellationException){throw e}
            catch(e:ApiException){if(e.status==401)Result.success()else Result.retry()}
            catch(e:Exception){Result.retry()}
            finally{db.close()}
        }
    }
    private fun notifyNew(ctx:Context,count:Int){
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(ctx,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val nm=ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("messages","پیام‌های جدید",NotificationManager.IMPORTANCE_DEFAULT))
        if(!nm.areNotificationsEnabled())return
        val intent=PendingIntent.getActivity(ctx,0,Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(101,NotificationCompat.Builder(ctx,"messages").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("پیام‌رسان من").setContentText(if(count==1)"پیام جدید دریافت شد" else "چند پیام جدید دریافت شد")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(intent).build())
    }
}
