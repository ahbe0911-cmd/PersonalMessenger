
const enc=new TextEncoder(),hex=b=>Array.from(new Uint8Array(b),x=>x.toString(16).padStart(2,'0')).join('');
const hash=async s=>hex(await crypto.subtle.digest('SHA-256',enc.encode(s)));
const random=()=>hex(crypto.getRandomValues(new Uint8Array(32)));
const isKey=k=>typeof k==='string'&&/^pk_[a-f0-9]{64}$/.test(k);
const enabled=env=>env.KEY_AUTH_OPEN==='true';
async function limit(db,k,n,now,fail){const r=await db.prepare('INSERT INTO key_rate(rate_key,bucket,hits) VALUES(?,?,1) ON CONFLICT(rate_key,bucket) DO UPDATE SET hits=hits+1 WHERE hits<? RETURNING hits').bind(k,Math.floor(now/600000),n).first();if(!r)fail(429,'TOO_MANY_REQUESTS')}
async function issue(db,account,device,now,expected,fail){const t=random(),sid=crypto.randomUUID();const r=await db.prepare('INSERT INTO sessions(id,account_id,token_hash,device_name,created_at,last_seen_at,expires_at) SELECT ?,a.id,?,?,?,?,? FROM accounts a JOIN key_identities k ON k.account_id=a.id WHERE a.id=? AND a.disabled_at IS NULL AND k.key_hash=?').bind(sid,await hash(t),device,now,now,now+30*86400000,account.id,expected).run();if(!r.meta.changes)fail(401,'INVALID_KEY');return {token:t,account,session_id:sid}}
export async function keyAuth(req,env,{json,fail,body}){
 const p=new URL(req.url).pathname,db=env.DB,now=Date.now();
 if(p==='/v2/auth/key-config'&&req.method==='GET')return json({enabled:enabled(env),method:'access_key'});
 if(!enabled(env))fail(503,'REGISTRATION_NOT_OPEN');
 if(req.method!=='POST')fail(405,'METHOD_NOT_ALLOWED');
 const ip=await hash('key-ip:'+(req.headers.get('CF-Connecting-IP')||'unknown'));await limit(db,'ip:'+ip,30,now,fail);await limit(db,'global',300,now,fail);
 const b=await body(req),device=typeof b?.device_name==='string'?b.device_name.trim():'';
 if(!device||device.length>80)fail(400,'INVALID_INPUT');
 if(p==='/v2/auth/key-register'){
  await limit(db,'register:'+ip,5,now,fail);await limit(db,'register-global',30,now,fail);
  const username=typeof b.username==='string'?b.username.trim().toLowerCase():'',name=typeof b.display_name==='string'?b.display_name.trim():'';
  if(!/^[a-z][a-z0-9_]{2,31}$/.test(username)||!name||name.length>80||b.accept_key_responsibility!==true)fail(400,'INVALID_PROFILE');
  const id=crypto.randomUUID(),access_key='pk_'+random(),digest=await hash(access_key),account={id,username,display_name:name,created_at:now};
  // Legacy email column is required by schema; this internal placeholder is not a verified address and never exposed.
  try{await db.batch([db.prepare("INSERT INTO accounts(id,email,username,display_name,created_at,verified_at) SELECT ?,?,?,?,?,? WHERE (SELECT count(*) FROM key_identities)<100").bind(id,id+'@key.invalid',username,name,now,0),db.prepare('INSERT INTO key_identities(account_id,key_hash,created_at) VALUES(?,?,?)').bind(id,digest,now)])}catch(e){if(/UNIQUE/i.test(String(e)))fail(409,'USERNAME_TAKEN');if(/FOREIGN KEY/i.test(String(e)))fail(507,'REGISTRATION_LIMIT');throw e}
  return json({...await issue(db,account,device,now,digest,fail),access_key},201);
 }
 if(p==='/v2/auth/key-login'){
  if(!isKey(b.key))fail(401,'INVALID_KEY');const digest=await hash(b.key);
  const account=await db.prepare('SELECT a.id,a.username,a.display_name,a.created_at FROM accounts a JOIN key_identities k ON k.account_id=a.id WHERE k.key_hash=? AND a.disabled_at IS NULL').bind(digest).first();
  if(!account)fail(401,'INVALID_KEY');
  return json(await issue(db,account,device,now,digest,fail),201);
 }
 fail(404,'NOT_FOUND');
}
export async function keySecurity(req,env,s,{json,fail,body}){
 const db=env.DB,id=s.account_id,p=new URL(req.url).pathname;
 const k=await db.prepare('SELECT key_hash FROM key_identities WHERE account_id=?').bind(id).first();if(!k)return null;
 if(p==='/v2/security'&&req.method==='GET')return json({method:'access_key'});
 if(p==='/v2/security/device-requests'&&req.method==='GET')return json({requests:[]});
 if(p==='/v2/security/revoke-others'&&req.method==='POST'){
  const now=Date.now();await limit(db,'security:'+id,10,now,fail);const b=await body(req);
  if(!isKey(b?.key)||await hash(b.key)!==k.key_hash)fail(403,'INVALID_KEY');
  await db.prepare('UPDATE sessions SET revoked_at=? WHERE account_id=? AND id<>? AND revoked_at IS NULL').bind(now,id,s.session_id).run();return json({ok:true});
 }
 return json({error:'NOT_FOUND'},404);
}
