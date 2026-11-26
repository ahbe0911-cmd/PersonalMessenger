import {auth,security} from './auth.js';

const json=(data,status=200)=>Response.json(data,{status,headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer'}});
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_FILE=20*1024*1024, ACCOUNT_QUOTA=50*1024*1024, PROJECT_QUOTA=300*1024*1024;
class HttpError extends Error{constructor(status,code){super(code);this.status=status;}}
const fail=(status,code)=>{throw new HttpError(status,code)};
async function hash(s){const a=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(s));return Array.from(new Uint8Array(a),x=>x.toString(16).padStart(2,'0')).join('')}
async function session(req,env){
 const m=/^Bearer ([0-9a-f]{64})$/.exec(req.headers.get('Authorization')||'');if(!m)fail(401,'UNAUTHORIZED');
 const row=await env.DB.prepare('SELECT s.id AS session_id,s.account_id,s.last_seen_at FROM sessions s JOIN accounts a ON a.id=s.account_id WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>? AND a.disabled_at IS NULL').bind(await hash(m[1]),Date.now()).first();
 if(!row)fail(401,'UNAUTHORIZED');if(row.last_seen_at<Date.now()-300000)await env.DB.prepare('UPDATE sessions SET last_seen_at=? WHERE id=? AND revoked_at IS NULL').bind(Date.now(),row.session_id).run();return row;
}
async function bounded(req,max){
 const len=Number(req.headers.get('content-length')||0);if(len>max)fail(413,'TOO_LARGE');
 const reader=req.body?.getReader();if(!reader)return new Uint8Array();
 let n=0,parts=[];try{while(true){const r=await reader.read();if(r.done)break;n+=r.value.length;if(n>max){await reader.cancel();fail(413,'TOO_LARGE')}parts.push(r.value)}}finally{reader.releaseLock()}
 const b=new Uint8Array(n);let off=0;for(const p of parts){b.set(p,off);off+=p.length}return b;
}
async function body(req){try{return JSON.parse(new TextDecoder().decode(await bounded(req,65536)))}catch(e){if(e instanceof HttpError)throw e;fail(400,'INVALID_JSON')}}
const fields='id,kind,name,body,mime,size,created_at';
async function handle(req,env){
 const u=new URL(req.url),p=u.pathname,m=req.method;
 if(m==='GET'&&p==='/health'){await env.DB.prepare('SELECT 1 AS ok').first();return json({ok:true,service:'peyvanik',stage:'security-preview',registration:'closed',cloud:'private-by-account'})}
 // Public account creation remains gated pending OAuth configuration and testing.
 if(p.startsWith('/v2/auth/'))return auth(req,env);
 const s=await session(req,env),owner=s.account_id,db=env.DB;
 if(p==='/v2/security'||p.startsWith('/v2/security/'))return security(req,env,s);
 if(p==='/v2/me'&&m==='GET')return json({account:await db.prepare('SELECT id,email,username,display_name,created_at FROM accounts WHERE id=?').bind(owner).first()});
 if(p==='/v2/sessions'&&m==='GET'){const r=await db.prepare('SELECT id,device_name,created_at,last_seen_at,expires_at FROM sessions WHERE account_id=? AND revoked_at IS NULL AND expires_at>? ORDER BY last_seen_at DESC').bind(owner,Date.now()).all();return json({sessions:r.results,current:s.session_id})}
 let match=/^\/v2\/sessions\/([0-9a-f-]{36})$/i.exec(p);
 if(match&&uuid.test(match[1])&&m==='DELETE'){const r=await db.prepare('UPDATE sessions SET revoked_at=? WHERE id=? AND account_id=? AND revoked_at IS NULL').bind(Date.now(),match[1],owner).run();if(!r.meta.changes)fail(404,'NOT_FOUND');return json({ok:true})}
 if(p==='/v2/cloud'&&m==='GET'){
  const requested=Number(u.searchParams.get('limit')||50),limit=Number.isInteger(requested)?Math.max(1,Math.min(100,requested)):50;
  const r=await db.prepare('SELECT '+fields+' FROM cloud_items WHERE owner_id=? AND state=? ORDER BY created_at DESC,id DESC LIMIT ?').bind(owner,'ready',limit).all();return json({items:r.results});
 }
 if(p==='/v2/cloud/notes'&&m==='POST'){
  const b=await body(req);if(typeof b?.text!=='string'||!b.text.trim()||b.text.length>16000)fail(400,'INVALID_NOTE');
  const name=typeof b.name==='string'?b.name.trim().slice(0,240):'یادداشت';const id=crypto.randomUUID(),now=Date.now();
  const r=await db.prepare("INSERT INTO cloud_items(id,owner_id,kind,name,body,created_at) SELECT ?,?,'note',?,?,? WHERE (SELECT count(*) FROM cloud_items WHERE owner_id=?)<2000").bind(id,owner,name||'یادداشت',b.text,now,owner).run();if(!r.meta.changes)fail(507,'ITEM_LIMIT');return json({item:{id,kind:'note',name,body:b.text,created_at:now}},201);
 }
 if(p==='/v2/cloud/files'&&m==='POST'){
  const name=(u.searchParams.get('name')||'file').replace(/[\x00-\x1f\x7f]/g,'_').slice(0,240);
  const mime=(req.headers.get('Content-Type')||'application/octet-stream').split(';')[0].slice(0,100);
  const data=await bounded(req,MAX_FILE);if(!data.length)fail(400,'EMPTY_FILE');
  const id=crypto.randomUUID(),key='cloud/'+owner+'/'+id,now=Date.now();
  const r=await db.prepare("INSERT INTO cloud_items(id,owner_id,kind,name,object_key,mime,size,created_at,state) SELECT ?,?,'file',?,?,?,?,?,'pending' WHERE (SELECT COALESCE(SUM(size),0) FROM cloud_items WHERE owner_id=?)+?<=? AND (SELECT COALESCE(SUM(size),0) FROM cloud_items)+?<=? AND (SELECT count(*) FROM cloud_items WHERE owner_id=?)<2000").bind(id,owner,name,key,mime,data.length,now,owner,data.length,ACCOUNT_QUOTA,data.length,PROJECT_QUOTA,owner).run();
  if(!r.meta.changes)fail(507,'QUOTA');
  // If storage or finalization fails, keep the reservation pending for later reconciliation; never expose partial uploads.
  try{await env.MEDIA.put(key,data);await db.prepare("UPDATE cloud_items SET state='ready' WHERE id=? AND owner_id=? AND state='pending'").bind(id,owner).run()}catch{fail(503,'STORAGE_PENDING')}
  return json({item:{id,kind:'file',name,mime,size:data.length,created_at:now}},201);
 }
 match=/^\/v2\/cloud\/([0-9a-f-]{36})(\/download)?$/i.exec(p);
 if(match&&uuid.test(match[1])){
  const item=await db.prepare('SELECT * FROM cloud_items WHERE id=? AND owner_id=?').bind(match[1],owner).first();if(!item)fail(404,'NOT_FOUND');
  if(m==='GET'&&match[2]){if(item.kind!=='file'||item.state!=='ready')fail(404,'NOT_FOUND');const data=await env.MEDIA.get(item.object_key,{type:'arrayBuffer'});if(!data)fail(503,'MEDIA_PENDING');return new Response(data,{headers:{'Content-Type':'application/octet-stream','Content-Disposition':"attachment; filename*=UTF-8''"+encodeURIComponent(item.name).replace(/['()]/g,c=>'%'+c.charCodeAt(0).toString(16)),'X-Media-Type':item.mime,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}})}
  if(m==='GET'&&!match[2]){if(item.state!=='ready')fail(404,'NOT_FOUND');return json({item:{id:item.id,kind:item.kind,name:item.name,body:item.body,mime:item.mime,size:item.size,created_at:item.created_at}})}
  if(m==='DELETE'&&!match[2]){
   if(item.state==='pending')fail(409,'UPLOAD_PENDING');
   await db.prepare("UPDATE cloud_items SET state='deleting' WHERE id=? AND owner_id=?").bind(item.id,owner).run();
   if(item.object_key)await env.MEDIA.delete(item.object_key);
   await db.prepare("DELETE FROM cloud_items WHERE id=? AND owner_id=? AND state='deleting'").bind(item.id,owner).run();return json({ok:true});
  }
 }
 if(p==='/v2/conversations'&&m==='GET'){
  const r=await db.prepare('SELECT c.id,c.kind,c.created_at FROM conversations c JOIN conversation_members cm ON cm.conversation_id=c.id WHERE cm.account_id=? ORDER BY c.created_at DESC LIMIT 100').bind(owner).all();return json({conversations:r.results});
 }
 match=/^\/v2\/conversations\/([0-9a-f-]{36})\/messages$/i.exec(p);
 if(match&&uuid.test(match[1])){
  const conversation=match[1];if(!await db.prepare('SELECT 1 FROM conversation_members WHERE conversation_id=? AND account_id=?').bind(conversation,owner).first())fail(404,'NOT_FOUND');
  if(m==='GET'){const r=await db.prepare('SELECT id,sender_id,body,created_at FROM chat_messages WHERE conversation_id=? AND deleted_at IS NULL ORDER BY created_at DESC,id DESC LIMIT 100').bind(conversation).all();return json({messages:r.results})}
  if(m==='POST'){const b=await body(req);if(typeof b?.text!=='string'||!b.text.trim()||b.text.length>16000)fail(400,'INVALID_MESSAGE');const id=crypto.randomUUID(),now=Date.now();await db.prepare('INSERT INTO chat_messages(id,conversation_id,sender_id,body,created_at) VALUES(?,?,?,?,?)').bind(id,conversation,owner,b.text,now).run();return json({id,sender_id:owner,body:b.text,created_at:now},201)}
 }
 return json({error:'NOT_FOUND'},404);
}
export default{async fetch(req,env){try{return await handle(req,env)}catch(e){return e instanceof HttpError?json({error:e.message},e.status):json({error:'SERVICE_UNAVAILABLE'},503)}}};
