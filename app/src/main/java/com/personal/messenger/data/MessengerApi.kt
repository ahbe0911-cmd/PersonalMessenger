package com.personal.messenger.data

import com.personal.messenger.model.ChatMessage
import com.personal.messenger.model.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.File
import java.io.InputStream
import java.time.Instant

data class Session(val id: String, val name: String, val token: String)
data class Delta(val id: String, val message: ChatMessage?)
data class SyncPage(val events: List<Delta>, val cursor: Long, val hasMore: Boolean)
class ApiException(val status: Int, val code: String) : Exception(code)

class MessengerApi {
    private val root = "https://personal-messenger-api.ahbe0911.workers.dev"
    private suspend fun request(path: String, method: String = "GET", token: String? = null,
                                body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val c = URL(root + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val status = c.responseCode
            val raw = (if (status in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            if (status !in 200..299) throw ApiException(status, json.optString("error", "HTTP_ERROR"))
            if (raw.isEmpty()) throw ApiException(502, "INVALID_RESPONSE")
            json
        } finally { c.disconnect() }
    }
    suspend fun enroll(name: String, secret: String): Session {
        val j = request("/v1/devices/enroll", "POST", secret, JSONObject().put("name", name))
        return Session(j.getString("id"), j.getString("name"), j.getString("token"))
    }
    suspend fun verify(session: Session) {
        val j = request("/v1/me", token = session.token).getJSONObject("device")
        if (j.getString("id") != session.id) throw ApiException(401, "UNAUTHORIZED")
    }
    suspend fun sync(session: Session, cursor: Long): SyncPage {
        val j = request("/v1/sync?after=$cursor&limit=200", token = session.token)
        val a = j.getJSONArray("events")
        val events = (0 until a.length()).map { i ->
            val m = a.getJSONObject(i)
            Delta(m.getString("id"), if (m.isNull("deleted_at")) parse(m, session.id) else null)
        }
        return SyncPage(events, j.getLong("cursor"), j.getBoolean("has_more"))
    }
    suspend fun send(session: Session, id: String, text: String, reply: String?): ChatMessage {
        val body = JSONObject().put("id", id).put("kind", "text").put("body", text)
            .put("reply_to", reply ?: JSONObject.NULL)
        return parse(request("/v1/messages", "POST", session.token, body).getJSONObject("message"), session.id)
    }
    suspend fun receipt(session: Session, id: String): ChatMessage =
        parse(request("/v1/messages/$id/receipt", "PUT", session.token,
            JSONObject().put("state", "read")).getJSONObject("message"), session.id)
    suspend fun delete(session: Session, id: String) {
        request("/v1/messages/$id", "DELETE", session.token)
    }
    suspend fun revoke(session: Session) {
        request("/v1/devices/self", "DELETE", session.token)
    }
    suspend fun upload(session: Session, file: File, name: String, mime: String, duration: Long?, progress: (Float)->Unit): String = withContext(Dispatchers.IO) {
        require(file.length() in 1..20971520)
        val q=URLEncoder.encode(name,"UTF-8")+(duration?.let { "&duration_ms="+it } ?: "")
        val c=URL(root+"/v1/media?name="+q).openConnection() as HttpURLConnection
        try {
            c.requestMethod="POST"; c.instanceFollowRedirects=false; c.connectTimeout=15000; c.readTimeout=90000
            c.doOutput=true; c.setRequestProperty("Authorization","Bearer "+session.token)
            c.setRequestProperty("Content-Type",mime); c.setFixedLengthStreamingMode(file.length())
            c.outputStream.use { out -> file.inputStream().use { input ->
                val buffer=ByteArray(65536);var total=0L
                while(true){val n=input.read(buffer);if(n<0)break;out.write(buffer,0,n);total+=n;progress(total.toFloat()/file.length())}
            } }
            val status=c.responseCode
            val raw=(if(status in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
            val j=runCatching{JSONObject(raw)}.getOrElse{JSONObject()}
            if(status !in 200..299)throw ApiException(status,j.optString("error","MEDIA_ERROR"))
            j.getString("id")
        } finally { c.disconnect() }
    }
    suspend fun sendMedia(session: Session,id:String,kind:String,name:String,attachment:String,reply:String?):ChatMessage {
        val b=JSONObject().put("id",id).put("kind",kind).put("body",name).put("attachment_id",attachment).put("reply_to",reply?:JSONObject.NULL)
        return parse(request("/v1/messages","POST",session.token,b).getJSONObject("message"),session.id)
    }
    suspend fun download(session:Session,id:String,destination:File,progress:(Float)->Unit):String=withContext(Dispatchers.IO){
        val c=URL(root+"/v1/media/"+id).openConnection() as HttpURLConnection
        try{
            c.instanceFollowRedirects=false;c.connectTimeout=15000;c.readTimeout=60000
            c.setRequestProperty("Authorization","Bearer "+session.token)
            val status=c.responseCode;if(status !in 200..299)throw ApiException(status,if(status==503)"MEDIA_PENDING" else "MEDIA_ERROR")
            val length=c.contentLengthLong
            c.inputStream.use{input->destination.outputStream().use{out->
                val buffer=ByteArray(65536);var total=0L
                while(true){val n=input.read(buffer);if(n<0)break;total+=n;if(total>20971520)throw ApiException(413,"BODY_TOO_LARGE");out.write(buffer,0,n);if(length>0)progress(total.toFloat()/length)}
            }}
            c.getHeaderField("X-Media-Type") ?: "application/octet-stream"
        }catch(e:Exception){destination.delete();throw e}finally{c.disconnect()}
    }
    private fun parse(j: JSONObject, device: String): ChatMessage {
        val kind = j.getString("kind")
        val text = j.optString("body").ifEmpty {
            when(kind) { "image" -> "تصویر"; "voice" -> "پیام صوتی"; "file" -> "فایل پیوست"; else -> "" }
        }
        return ChatMessage(id = j.getString("id"), text = text,
            timestamp = Instant.parse(j.getString("created_at")).toEpochMilli(),
            outgoing = j.getString("sender_device") == device,
            status = when(j.optInt("status")) { 2 -> MessageStatus.READ; 1 -> MessageStatus.DELIVERED; else -> MessageStatus.SENT },
            replyTo = if(j.isNull("reply_to")) null else j.getString("reply_to"),
            kind=kind, attachmentId=if(j.isNull("attachment_id")) null else j.getString("attachment_id"))
    }
}
