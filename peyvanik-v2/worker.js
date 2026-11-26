import {auth,security} from './auth.js';
import {chat} from './chat.js';
import {cloud} from './cloud.js';
import {keyAuth,keySecurity} from './key-auth.js';

const json=(data,status=200)=>Response.json(data,{status,headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer'}});
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
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
async function handle(req,env){
 const u=new URL(req.url),p=u.pathname,m=req.method;
 if(m==='GET'&&p==='/health'){await env.DB.prepare('SELECT 1 AS ok').first();return json({ok:true,service:'peyvanik',stage:'cloud-preview',registration:'closed',cloud:'private-by-account'})}
 // Public account creation remains gated pending OAuth configuration and testing.
 if(p.startsWith('/v2/auth/key-'))return keyAuth(req,env,{json,fail,body});
 if(p.startsWith('/v2/auth/'))return auth(req,env);
 const s=await session(req,env),owner=s.account_id,db=env.DB;
 if(p==='/v2/security'||p.startsWith('/v2/security/'))return await keySecurity(req,env,s,{json,fail,body})||security(req,env,s);
 if(p==='/v2/me'&&m==='GET')return json({account:await db.prepare("SELECT id,CASE WHEN email LIKE '%@key.invalid' THEN NULL ELSE email END AS email,username,display_name,created_at FROM accounts WHERE id=?").bind(owner).first()});
 if(p==='/v2/sessions'&&m==='GET'){const r=await db.prepare('SELECT id,device_name,created_at,last_seen_at,expires_at FROM sessions WHERE account_id=? AND revoked_at IS NULL AND expires_at>? ORDER BY last_seen_at DESC').bind(owner,Date.now()).all();return json({sessions:r.results,current:s.session_id})}
 let match=/^\/v2\/sessions\/([0-9a-f-]{36})$/i.exec(p);
 if(match&&uuid.test(match[1])&&m==='DELETE'){const r=await db.prepare('UPDATE sessions SET revoked_at=? WHERE id=? AND account_id=? AND revoked_at IS NULL').bind(Date.now(),match[1],owner).run();if(!r.meta.changes)fail(404,'NOT_FOUND');return json({ok:true})}
 if(p==='/v2/cloud'||p.startsWith('/v2/cloud/'))return cloud(req,env,owner,{json,fail,body,bounded});
 if(p==='/v2/profile'||p.startsWith('/v2/users/')||p==='/v2/blocks'||p.startsWith('/v2/blocks/')||p==='/v2/conversations'||p.startsWith('/v2/conversations/'))return chat(req,db,owner,{json,fail,body});
 return json({error:'NOT_FOUND'},404);
}
export default{async fetch(req,env){try{return await handle(req,env)}catch(e){return e instanceof HttpError?json({error:e.message},e.status):json({error:'SERVICE_UNAVAILABLE'},503)}}};
