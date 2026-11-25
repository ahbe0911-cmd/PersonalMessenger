package com.personal.messenger.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.personal.messenger.model.*
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private class CacheCipher {
    private val key:SecretKey by lazy {
        val alias="personal_messenger_cache_v1"
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey(alias,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run{
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }
    fun encrypt(id:String,plain:String):ByteArray{
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key)
        c.updateAAD(id.toByteArray(Charsets.UTF_8))
        return c.iv+c.doFinal(plain.toByteArray(Charsets.UTF_8))
    }
    fun decrypt(id:String,blob:ByteArray):String{
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,blob.copyOfRange(0,12)))
        c.updateAAD(id.toByteArray(Charsets.UTF_8))
        return String(c.doFinal(blob.copyOfRange(12,blob.size)),Charsets.UTF_8)
    }
}
class LocalChatStore(context:Context):SQLiteOpenHelper(context,"personal_chat_v1.db",null,1){
    private val cipher=CacheCipher()
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY,ts INTEGER NOT NULL,state TEXT NOT NULL,payload BLOB NOT NULL)")
        db.execSQL("CREATE INDEX messages_time ON messages(ts,id)")
        db.execSQL("CREATE INDEX messages_state ON messages(state,ts)")
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY,v TEXT NOT NULL)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){error("Unsupported cache migration")}
    private fun meta(db:SQLiteDatabase,k:String):String?=db.rawQuery("SELECT v FROM meta WHERE k=?",arrayOf(k)).use{if(it.moveToFirst())it.getString(0)else null}
    private fun meta(db:SQLiteDatabase,k:String,v:String){db.insertWithOnConflict("meta",null,ContentValues().apply{put("k",k);put("v",v)},SQLiteDatabase.CONFLICT_REPLACE)}
    private inline fun <T> transaction(block:(SQLiteDatabase)->T):T{
        val db=writableDatabase;db.beginTransaction()
        try{val r=block(db);db.setTransactionSuccessful();return r}finally{db.endTransaction()}
    }
    fun prepare(owner:String)=transaction{db->
        if(meta(db,"owner")!=owner){db.delete("messages",null,null);db.delete("meta",null,null);meta(db,"owner",owner)}
    }
    fun clear()=transaction{db->db.delete("messages",null,null);db.delete("meta",null,null);Unit}
    fun cursor():Long=meta(readableDatabase,"cursor")?.toLongOrNull()?:0L
    fun noticeCursor():Long?=meta(readableDatabase,"notice")?.toLongOrNull()
    fun markNotice(cursor:Long)=transaction{db->meta(db,"notice",maxOf(cursor,meta(db,"notice")?.toLongOrNull()?:0L).toString())}
    private fun put(db:SQLiteDatabase,m:ChatMessage){
        val j=JSONObject().put("text",m.text).put("outgoing",m.outgoing).put("reply",m.replyTo?:JSONObject.NULL)
            .put("kind",m.kind).put("attachment",m.attachmentId?:JSONObject.NULL)
        val v=ContentValues().apply{put("id",m.id);put("ts",m.timestamp);put("state",m.status.name);put("payload",cipher.encrypt(m.id,j.toString()))}
        check(db.insertWithOnConflict("messages",null,v,SQLiteDatabase.CONFLICT_REPLACE)!=-1L)
    }
    fun put(m:ChatMessage)=transaction{put(it,m)}
    fun remove(id:String)=transaction{it.delete("messages","id=?",arrayOf(id));Unit}
    fun applyPage(page:SyncPage)=transaction{db->
        for(e in page.events){val m=e.message;if(m==null)db.delete("messages","id=?",arrayOf(e.id))else put(db,m)}
        meta(db,"cursor",page.cursor.toString())
    }
    private fun read(where:String?,args:Array<String>?,limit:String?=null):List<ChatMessage>{
        val out=mutableListOf<ChatMessage>()
        readableDatabase.query("messages",arrayOf("id","ts","state","payload"),where,args,null,null,"ts ASC,id ASC",limit).use{c->
            while(c.moveToNext()){
                val id=c.getString(0);val j=JSONObject(cipher.decrypt(id,c.getBlob(3)))
                out.add(ChatMessage(id=id,text=j.getString("text"),timestamp=c.getLong(1),outgoing=j.getBoolean("outgoing"),
                    status=MessageStatus.valueOf(c.getString(2)),replyTo=if(j.isNull("reply"))null else j.getString("reply"),
                    kind=j.getString("kind"),attachmentId=if(j.isNull("attachment"))null else j.getString("attachment")))
            }
        }
        return out
    }
    fun messages()=read(null,null)
    fun queued()=read("state=?",arrayOf(MessageStatus.QUEUED.name),"20")
}
