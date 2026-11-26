
const enc=new TextEncoder(),dec=new TextDecoder(), ALPHABET='ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
const hex=b=>Array.from(new Uint8Array(b),x=>x.toString(16).padStart(2,'0')).join('');
const random=n=>crypto.getRandomValues(new Uint8Array(n));
const token=()=>hex(random(32));
const hash=async s=>hex(await crypto.subtle.digest('SHA-256',enc.encode(s)));
const b64u=b=>btoa(String.fromCharCode(...new Uint8Array(b))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
const unb64u=s=>Uint8Array.from(atob(s.replace(/-/g,'+').replace(/_/g,'/')+'='.repeat((4-s.length%4)%4)),c=>c.charCodeAt(0));
const reply=(v,status=200)=>Response.json(v,{status,headers:{'Cache-Control':'no-store','Pragma':'no-cache','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer'}});
class AuthError extends Error{constructor(status,code){super(code);this.status=status}}
function fail(status,code){throw new AuthError(status,code)}
export function base32(bytes){let bits=0,value=0,out='';for(const b of bytes){value=(value<<8)|b;bits+=8;while(bits>=5){out+=ALPHABET[(value>>>(bits-5))&31];bits-=5}}if(bits)out+=ALPHABET[(value<<(5-bits))&31];return out}
export function decode32(s){let bits=0,value=0,out=[];for(const ch of s.replace(/=+$/,'').toUpperCase()){const n=ALPHABET.indexOf(ch);if(n<0)throw Error('bad base32');value=(value<<5)|n;bits+=5;if(bits>=8){out.push((value>>>(bits-8))&255);bits-=8}}return new Uint8Array(out)}
export async function totp(secret,step,digits=6){
 if(!Number.isSafeInteger(step)||step<0)throw Error('bad step');
 const counter=new ArrayBuffer(8);new DataView(counter).setBigUint64(0,BigInt(step),false);
 const key=await crypto.subtle.importKey('raw',decode32(secret),{name:'HMAC',hash:'SHA-1'},false,['sign']);
 const mac=new Uint8Array(await crypto.subtle.sign('HMAC',key,counter)),off=mac[mac.length-1]&15;
 const n=((mac[off]&127)*0x1000000+mac[off+1]*0x10000+mac[off+2]*0x100+mac[off+3])%(10**digits);
 return String(n).padStart(digits,'0');
}
function equal(a,b){if(typeof a!=='string'||typeof b!=='string'||a.length!==b.length)return false;let diff=0;for(let i=0;i<a.length;i++)diff|=a.charCodeAt(i)^b.charCodeAt(i);return diff===0}
export async function matchStep(secret,code,now=Date.now(),last=-1){
 if(!/^\d{6}$/.test(code))return null;const current=Math.floor(now/30000);let match=null;
 for(const step of [current-1,current,current+1])if(step>=0&&step>last&&equal(await totp(secret,step),code))match=step;
 return match;
}
async function aes(env){
 const s=env.TOTP_ENCRYPTION_KEY;if(typeof s!=='string'||!/^[a-f0-9]{64}$/.test(s))fail(503,'AUTH_NOT_CONFIGURED');
 return crypto.subtle.importKey('raw',Uint8Array.from(s.match(/../g),x=>parseInt(x,16)),{name:'AES-GCM'},false,['encrypt','decrypt']);
}
async function seal(env,value,aad){const iv=random(12),ct=await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:enc.encode(aad)},await aes(env),enc.encode(value));return b64u(iv)+'.'+b64u(ct)}
async function unseal(env,value,aad){const [iv,ct]=value.split('.');return dec.decode(await crypto.subtle.decrypt({name:'AES-GCM',iv:unb64u(iv),additionalData:enc.encode(aad)},await aes(env),unb64u(ct)))}
async function read(req){
 const max=16384,len=Number(req.headers.get('content-length')||0);if(len>max)fail(413,'TOO_LARGE');
 const r=req.body?.getReader();if(!r)fail(400,'INVALID_JSON');let n=0,chunks=[];
 try{while(true){const a=await r.read();if(a.done)break;n+=a.value.length;if(n>max){await r.cancel();fail(413,'TOO_LARGE')}chunks.push(a.value)}}finally{r.releaseLock()}
 const b=new Uint8Array(n);let i=0;for(const c of chunks){b.set(c,i);i+=c.length}
 try{const parsed=JSON.parse(dec.decode(b));if(!parsed||typeof parsed!=='object'||Array.isArray(parsed))throw Error();return parsed}catch{fail(400,'INVALID_JSON')}
}
async function rate(db,key,limit,period=600000){
 const bucket=Math.floor(Date.now()/period);
 const r=await db.prepare('INSERT INTO auth_rate(rate_key,bucket,hits) VALUES(?,?,1) ON CONFLICT(rate_key,bucket) DO UPDATE SET hits=hits+1 WHERE hits<? RETURNING hits').bind(key,bucket,limit).first();
 if(!r)fail(429,'TOO_MANY_ATTEMPTS');
}
let cachedKeys=null,keysUntil=0;
async function jwks(force=false){
 if(!force&&cachedKeys&&Date.now()<keysUntil)return cachedKeys;
 const r=await fetch('https://www.googleapis.com/oauth2/v3/certs',{redirect:'error',signal:AbortSignal.timeout(5000)});
 if(!r.ok)fail(503,'GOOGLE_UNAVAILABLE');const j=await r.json();if(!Array.isArray(j.keys))fail(503,'GOOGLE_UNAVAILABLE');
 const seconds=Number((r.headers.get('cache-control')||'').match(/max-age=(\d+)/)?.[1]||300);
 cachedKeys=j.keys;keysUntil=Date.now()+Math.min(seconds,3600)*1000;return cachedKeys;
}
// Strict RS256 verification, fixed Google JWKS endpoint, audience, issuer, expiry, nonce and verified Gmail.
export async function verifyGoogle(jwt,audience,nonce,resolveKeys=jwks,now=Date.now()){
 if(typeof jwt!=='string'||jwt.length>12000)fail(401,'INVALID_GOOGLE_TOKEN');
 const parts=jwt.split('.');if(parts.length!==3||parts.some(p=>!p||!/^[A-Za-z0-9_-]+$/.test(p)))fail(401,'INVALID_GOOGLE_TOKEN');
 let header,claims;try{header=JSON.parse(dec.decode(unb64u(parts[0])));claims=JSON.parse(dec.decode(unb64u(parts[1])))}catch{fail(401,'INVALID_GOOGLE_TOKEN')}
 if(!header||!claims||header.alg!=='RS256'||typeof header.kid!=='string'||header.kid.length>128)fail(401,'INVALID_GOOGLE_TOKEN');
 const keys=await resolveKeys();let key=keys.find(k=>k.kid===header.kid&&k.kty==='RSA'&&(!k.alg||k.alg==='RS256')&&(!k.use||k.use==='sig'));
 if(!key){key=(await resolveKeys(true)).find(k=>k.kid===header.kid&&k.kty==='RSA'&&(!k.alg||k.alg==='RS256')&&(!k.use||k.use==='sig'))}
 if(!key)fail(401,'INVALID_GOOGLE_TOKEN');
 let valid=false;try{const pub=await crypto.subtle.importKey('jwk',key,{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['verify']);valid=await crypto.subtle.verify('RSASSA-PKCS1-v1_5',pub,unb64u(parts[2]),enc.encode(parts[0]+'.'+parts[1]))}catch{fail(401,'INVALID_GOOGLE_TOKEN')}
 const sec=Math.floor(now/1000);
 if(!valid||!['accounts.google.com','https://accounts.google.com'].includes(claims.iss)||claims.aud!==audience||(claims.azp&&claims.azp!==audience)||!Number.isInteger(claims.exp)||claims.exp<=sec||!Number.isInteger(claims.iat)||claims.iat>sec+60||claims.iat<sec-600||claims.nonce!==nonce||typeof claims.sub!=='string'||!claims.sub||claims.sub.length>255||claims.email_verified!==true||typeof claims.email!=='string'||!/^[a-z0-9.!#$%&'*+/=?^_\x60{|}~-]+@gmail\.com$/i.test(claims.email))fail(401,'INVALID_GOOGLE_TOKEN');
 return {sub:claims.sub,email:claims.email.toLowerCase(),name:typeof claims.name==='string'?claims.name.slice(0,80):''};
}
const isTicket=x=>typeof x==='string'&&/^[a-f0-9]{64}$/.test(x);
const configured=env=>env.REGISTRATION_OPEN==='true'&&typeof env.GOOGLE_CLIENT_ID==='string'&&/^[a-z0-9-]+\.apps\.googleusercontent\.com$/.test(env.GOOGLE_CLIENT_ID)&&typeof env.TOTP_ENCRYPTION_KEY==='string'&&/^[a-f0-9]{64}$/.test(env.TOTP_ENCRYPTION_KEY);

const PASSWORD_ITERATIONS=100000;
export function newDeviceCode(){
 // Rejection sampling avoids modulo bias.
 const b=new Uint32Array(1);do{crypto.getRandomValues(b)}while(b[0]>=4294000000);
 return String(b[0]%1000000).padStart(6,'0');
}
export function newRecoveryCode(){return hex(random(16)).toUpperCase().match(/.{4}/g).join('-')}
function normalizeRecovery(s){return typeof s==='string'&&/^[0-9a-fA-F-]{32,39}$/.test(s)?s.replace(/-/g,'').toLowerCase():''}
async function recoveryHash(s){const v=normalizeRecovery(s);return v.length===32?await hash('peyvanik-recovery:'+v):null}
function validPassword(s){return typeof s==='string'&&s.length>=12&&s.length<=128}
export async function passwordDigest(env,password,salt,iterations=PASSWORD_ITERATIONS){
 if(!validPassword(password)||!Number.isInteger(iterations)||iterations!==PASSWORD_ITERATIONS||!/^[a-f0-9]{32}$/.test(salt))fail(400,'INVALID_PASSWORD');
 // Server-side pepper protects against database-only compromise; keep the encryption key outside D1.
 const pepper=await crypto.subtle.importKey('raw',enc.encode(env.TOTP_ENCRYPTION_KEY),{name:'HMAC',hash:'SHA-256'},false,['sign']);
 const input=await crypto.subtle.sign('HMAC',pepper,enc.encode('peyvanik-password-v1:'+password));
 const key=await crypto.subtle.importKey('raw',input,'PBKDF2',false,['deriveBits']);
 return hex(await crypto.subtle.deriveBits({name:'PBKDF2',hash:'SHA-256',salt:enc.encode(salt),iterations},key,256));
}
async function requirePassword(env,account,p){
 if(!account.password_hash)return;
 if(!validPassword(p)||!equal(await passwordDigest(env,p,account.password_salt,account.password_iterations),account.password_hash))fail(401,'PASSWORD_REQUIRED_OR_INVALID');
}
async function issueSession(db,id,c,now,passwordHash){
 const session=token(),sid=crypto.randomUUID(),expires=now+30*86400000;
 // Recheck account security settings at session issuance; do not accept a stale password check.
 const r=await db.prepare('INSERT INTO sessions(id,account_id,token_hash,device_name,created_at,last_seen_at,expires_at) SELECT ?,id,?,?,?,?,? FROM accounts WHERE id=? AND disabled_at IS NULL AND password_hash IS ?').bind(sid,await hash(session),c.device_name,now,now,expires,id,passwordHash||null).run();
 if(!r.meta.changes)fail(401,'ACCOUNT_SECURITY_CHANGED');
 return reply({token:session,session_id:sid,expires_at:expires,account:{id,email:c.email,display_name:c.display_name}},201);
}
async function takeTotp(env,account,code,now){
 const row=await env.DB.prepare('SELECT totp_cipher,last_totp_step FROM google_identities WHERE account_id=?').bind(account).first();
 if(!row)fail(401,'ACCOUNT_UNAVAILABLE');
 const step=await matchStep(await unseal(env,row.totp_cipher,'account:'+account),typeof code==='string'?code:'',now,row.last_totp_step);
 if(step===null)fail(401,'INVALID_OR_USED_CODE');
 const r=await env.DB.prepare('UPDATE google_identities SET last_totp_step=? WHERE account_id=? AND last_totp_step<?').bind(step,account,step).run();
 if(!r.meta.changes)fail(401,'INVALID_OR_USED_CODE');
}
export async function security(req,env,session){
 try{
  if(!configured(env))fail(503,'REGISTRATION_NOT_OPEN');
  const db=env.DB,now=Date.now(),id=session.account_id,path=new URL(req.url).pathname,method=req.method;
  const account=await db.prepare('SELECT password_hash,password_salt,password_iterations FROM accounts WHERE id=? AND disabled_at IS NULL').bind(id).first();
  if(!account)fail(401,'ACCOUNT_UNAVAILABLE');
  if(path==='/v2/security'&&method==='GET'){
   const n=await db.prepare('SELECT count(*) AS n FROM recovery_codes WHERE account_id=? AND consumed_at IS NULL').bind(id).first();
   return reply({password_enabled:!!account.password_hash,recovery_remaining:n.n,authenticator_enabled:true});
  }
  if(path==='/v2/security/device-requests'&&method==='GET'){
   // Only an authenticated session of the same account sees codes; never put them into chat history.
   const rows=await db.prepare('SELECT d.ticket_hash,d.code_cipher,c.device_name,d.created_at,d.expires_at FROM device_login_codes d JOIN totp_challenges c ON c.ticket_hash=d.ticket_hash WHERE d.account_id=? AND c.account_id=? AND c.consumed_at IS NULL AND c.attempts<5 AND d.denied_at IS NULL AND d.expires_at>? AND c.expires_at>? ORDER BY d.created_at DESC LIMIT 8').bind(id,id,now,now).all();
   const requests=[];for(const r of rows.results)requests.push({id:r.ticket_hash,code:await unseal(env,r.code_cipher,'device:'+r.ticket_hash),device_name:r.device_name,created_at:r.created_at,expires_at:r.expires_at});
   return reply({requests});
  }
  if(method!=='POST')fail(405,'METHOD_NOT_ALLOWED');
  await rate(db,'security:'+id,10);
  const b=await read(req);
  if(path==='/v2/security/device-deny'){
   if(!isTicket(b.id))fail(400,'INVALID_INPUT');
   const r=await db.prepare('UPDATE device_login_codes SET denied_at=? WHERE ticket_hash=? AND account_id=? AND denied_at IS NULL AND expires_at>?').bind(now,b.id,id,now).run();
   if(!r.meta.changes)fail(404,'NOT_FOUND');return reply({ok:true});
  }
  if(!['/v2/security/password','/v2/security/recovery','/v2/security/revoke-others'].includes(path))fail(404,'NOT_FOUND');
  await requirePassword(env,account,b.current_password);
  if(path==='/v2/security/password'&&b.new_password!==null&&!validPassword(b.new_password))fail(400,'PASSWORD_MIN_12');
  await takeTotp(env,id,b.code,now);
  if(path==='/v2/security/password'){
   const salt=b.new_password===null?null:hex(random(16)),digest=salt?await passwordDigest(env,b.new_password,salt):null;
   const r=await db.prepare('UPDATE accounts SET password_hash=?,password_salt=?,password_iterations=? WHERE id=? AND password_hash IS ?').bind(digest,salt,salt?PASSWORD_ITERATIONS:null,id,account.password_hash).run();
   if(!r.meta.changes)fail(409,'ACCOUNT_SECURITY_CHANGED');
   // Changing the extra password revokes other sessions and outstanding login challenges.
   await db.batch([db.prepare('UPDATE sessions SET revoked_at=? WHERE account_id=? AND id<>? AND revoked_at IS NULL').bind(now,id,session.session_id),db.prepare('UPDATE totp_challenges SET consumed_at=?,pending_cipher=NULL WHERE account_id=? AND consumed_at IS NULL').bind(now,id)]);
   return reply({ok:true,password_enabled:!!digest});
  }
  if(path==='/v2/security/recovery'){
   const codes=Array.from({length:8},newRecoveryCode),hashes=await Promise.all(codes.map(recoveryHash));
   await db.batch([db.prepare('DELETE FROM recovery_codes WHERE account_id=?').bind(id),...hashes.map(h=>db.prepare('INSERT INTO recovery_codes(account_id,code_hash,created_at) VALUES(?,?,?)').bind(id,h,now))]);
   return reply({codes});
  }
  if(path==='/v2/security/revoke-others'){
   await db.batch([db.prepare('UPDATE sessions SET revoked_at=? WHERE account_id=? AND id<>? AND revoked_at IS NULL').bind(now,id,session.session_id),db.prepare('UPDATE totp_challenges SET consumed_at=?,pending_cipher=NULL WHERE account_id=? AND consumed_at IS NULL').bind(now,id)]);
   return reply({ok:true});
  }
 }catch(e){return e instanceof AuthError?reply({error:e.message},e.status):reply({error:'SECURITY_UNAVAILABLE'},503)}
}

export async function auth(req,env){
 try{
  const path=new URL(req.url).pathname,db=env.DB;
  if(path==='/v2/auth/config'&&req.method==='GET')return reply({enabled:configured(env),method:'google_totp',manual_setup:true,google_client_id:configured(env)?env.GOOGLE_CLIENT_ID:null});
  if(!configured(env))fail(503,'REGISTRATION_NOT_OPEN');
  if(req.method!=='POST')fail(405,'METHOD_NOT_ALLOWED');
  const now=Date.now(),ip=await hash(env.TOTP_ENCRYPTION_KEY+':'+(req.headers.get('CF-Connecting-IP')||'unknown'));
  await rate(db,'ip:'+ip,30);await rate(db,'global',500,600000);
  const b=await read(req);
  if(path==='/v2/auth/nonce'){
   const nonce=token();await db.prepare('INSERT INTO oidc_nonces(nonce_hash,created_at,expires_at) VALUES(?,?,?)').bind(await hash(nonce),now,now+300000).run();
   return reply({nonce,expires_in:300},201);
  }
  if(path==='/v2/auth/google'){
   if(!isTicket(b.nonce)||typeof b.device_name!=='string'||!b.device_name.trim()||b.device_name.length>80)fail(400,'INVALID_INPUT');
   const nh=await hash(b.nonce);
   if(!await db.prepare('SELECT 1 FROM oidc_nonces WHERE nonce_hash=? AND expires_at>? AND consumed_at IS NULL').bind(nh,now).first())fail(401,'INVALID_NONCE');
   const g=await verifyGoogle(b.id_token,env.GOOGLE_CLIENT_ID,b.nonce);
   await rate(db,'sub:'+await hash(g.sub),8);
   const consumed=await db.prepare('UPDATE oidc_nonces SET consumed_at=? WHERE nonce_hash=? AND expires_at>? AND consumed_at IS NULL').bind(now,nh,now).run();if(!consumed.meta.changes)fail(401,'INVALID_NONCE');
   const account=await db.prepare('SELECT a.id,a.email,a.disabled_at,a.password_hash FROM google_identities i JOIN accounts a ON a.id=i.account_id WHERE i.google_sub=?').bind(g.sub).first();
   if(account?.disabled_at||account&&account.email!==g.email)fail(403,'ACCOUNT_UNAVAILABLE');
   const ticket=token(),th=await hash(ticket),mode=account?'login':'setup',secret=account?null:base32(random(20));
   // Never return a stored TOTP seed for an existing account.
   const cipher=secret?await seal(env,secret,'setup:'+th):null;
   await db.prepare('INSERT INTO totp_challenges(ticket_hash,google_sub,email,display_name,device_name,mode,account_id,pending_cipher,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)').bind(th,g.sub,g.email,g.name,b.device_name.trim(),mode,account?.id||null,cipher,now,now+300000).run();
   let deviceAvailable=false;
   if(account){
    const active=await db.prepare('SELECT 1 FROM sessions WHERE account_id=? AND revoked_at IS NULL AND expires_at>? LIMIT 1').bind(account.id,now).first();
    if(active){
     const code=newDeviceCode(),cipher=await seal(env,code,'device:'+th);
     await db.prepare('INSERT INTO device_login_codes(ticket_hash,account_id,code_cipher,created_at,expires_at) VALUES(?,?,?,?,?)').bind(th,account.id,cipher,now,now+300000).run();
     deviceAvailable=true;
    }
   }
   return reply({ticket,mode,expires_in:300,password_required:!!account?.password_hash,device_available:deviceAvailable,recovery_available:!!account,...(secret?{setup_key:secret,issuer:'Peyvanik',account:g.email,algorithm:'SHA1',digits:6,period:30}:{})});
  }
  if(['/v2/auth/totp','/v2/auth/device','/v2/auth/recovery'].includes(path)){
   if(!isTicket(b.ticket)||typeof b.code!=='string'||(path==='/v2/auth/recovery'?!normalizeRecovery(b.code):!/^\d{6}$/.test(b.code)))fail(400,'INVALID_INPUT');
   const th=await hash(b.ticket);
   const c=await db.prepare('UPDATE totp_challenges SET attempts=attempts+1 WHERE ticket_hash=? AND consumed_at IS NULL AND expires_at>? AND attempts<5 RETURNING *').bind(th,now).first();if(!c)fail(401,'CHALLENGE_EXPIRED');
   await rate(db,'totp:'+await hash(c.google_sub),10);
   let id=c.account_id,account=null,step=null,secret=null,recovery=null;
   if(c.mode==='setup'){
    if(path!=='/v2/auth/totp'||!c.pending_cipher)fail(401,'CHALLENGE_EXPIRED');
    secret=await unseal(env,c.pending_cipher,'setup:'+th);step=await matchStep(secret,b.code,now);
    if(step===null)fail(401,'INVALID_OR_USED_CODE');
   }else{
    account=await db.prepare('SELECT i.totp_cipher,i.last_totp_step,a.disabled_at,a.password_hash,a.password_salt,a.password_iterations FROM google_identities i JOIN accounts a ON a.id=i.account_id WHERE i.google_sub=? AND i.account_id=?').bind(c.google_sub,id).first();
    if(!account||account.disabled_at)fail(401,'ACCOUNT_UNAVAILABLE');
    await requirePassword(env,account,b.password);
    if(path==='/v2/auth/totp'){
     secret=await unseal(env,account.totp_cipher,'account:'+id);step=await matchStep(secret,b.code,now,account.last_totp_step);
     if(step===null)fail(401,'INVALID_OR_USED_CODE');
    }else if(path==='/v2/auth/device'){
     const d=await db.prepare('SELECT code_cipher FROM device_login_codes WHERE ticket_hash=? AND account_id=? AND denied_at IS NULL AND expires_at>? AND EXISTS(SELECT 1 FROM sessions WHERE account_id=? AND revoked_at IS NULL AND expires_at>?)').bind(th,id,now,id,now).first();
     if(!d||!equal(await unseal(env,d.code_cipher,'device:'+th),b.code))fail(401,'INVALID_OR_USED_CODE');
    }else{
     recovery=await recoveryHash(b.code);if(!recovery||!await db.prepare('SELECT 1 FROM recovery_codes WHERE account_id=? AND code_hash=? AND consumed_at IS NULL').bind(id,recovery).first())fail(401,'INVALID_OR_USED_CODE');
    }
   }
   // Atomic challenge consumption; for device login also check that denial did not win the race.
   const used=path==='/v2/auth/device'
    ?await db.prepare('UPDATE totp_challenges SET consumed_at=?,pending_cipher=NULL WHERE ticket_hash=? AND consumed_at IS NULL AND expires_at>? AND EXISTS(SELECT 1 FROM device_login_codes WHERE ticket_hash=? AND account_id=? AND denied_at IS NULL AND expires_at>?) AND EXISTS(SELECT 1 FROM sessions WHERE account_id=? AND revoked_at IS NULL AND expires_at>?)').bind(now,th,now,th,id,now,id,now).run()
    :await db.prepare('UPDATE totp_challenges SET consumed_at=?,pending_cipher=NULL WHERE ticket_hash=? AND consumed_at IS NULL AND expires_at>?').bind(now,th,now).run();
   if(!used.meta.changes)fail(401,'CHALLENGE_EXPIRED');
   if(c.mode==='setup'){
    id=crypto.randomUUID();const cipher=await seal(env,secret,'account:'+id);
    try{await db.batch([db.prepare('INSERT INTO accounts(id,email,display_name,created_at,verified_at) VALUES(?,?,?,?,?)').bind(id,c.email,c.display_name,now,now),db.prepare('INSERT INTO google_identities(google_sub,account_id,totp_cipher,last_totp_step) VALUES(?,?,?,?)').bind(c.google_sub,id,cipher,step)])}catch{fail(409,'SETUP_ALREADY_COMPLETED')}
   }else if(step!==null){
    const updated=await db.prepare('UPDATE google_identities SET last_totp_step=? WHERE google_sub=? AND account_id=? AND last_totp_step<?').bind(step,c.google_sub,id,step).run();
    if(!updated.meta.changes)fail(401,'INVALID_OR_USED_CODE');
   }else if(recovery){
    const updated=await db.prepare('UPDATE recovery_codes SET consumed_at=? WHERE account_id=? AND code_hash=? AND consumed_at IS NULL').bind(now,id,recovery).run();
    if(!updated.meta.changes)fail(401,'INVALID_OR_USED_CODE');
   }
   return issueSession(db,id,c,now,account?.password_hash);
  }
  fail(404,'NOT_FOUND');
 }catch(e){return e instanceof AuthError?reply({error:e.message},e.status):reply({error:'AUTH_UNAVAILABLE'},503)}
}
