
import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {newDeviceCode,newRecoveryCode,passwordDigest,security,auth} from './auth.js';
import worker from './worker.js';
function makeDB(){
 const raw=new DatabaseSync(':memory:');raw.exec('PRAGMA foreign_keys=ON');
 for(const p of ['schema.sql','002-auth.sql','003-security.sql'])raw.exec(readFileSync(new URL(p,import.meta.url),'utf8'));
 const db={prepare(sql){const make=args=>({first:async()=>raw.prepare(sql).get(...args)??null,all:async()=>({results:raw.prepare(sql).all(...args)}),run:async()=>({meta:{changes:Number(raw.prepare(sql).run(...args).changes)}}),sql,args});return make([]).bind?make([]):{...make([]),bind:(...args)=>make(args)}},async batch(items){raw.exec('BEGIN');try{const r=[];for(const i of items)r.push(await i.run());raw.exec('COMMIT');return r}catch(e){raw.exec('ROLLBACK');throw e}}};
 return {raw,db};
}
const sha=async s=>Buffer.from(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(s))).toString('hex');
const envFor=db=>({DB:db,REGISTRATION_OPEN:'true',GOOGLE_CLIENT_ID:'test.apps.googleusercontent.com',TOTP_ENCRYPTION_KEY:'ab'.repeat(32)});
const request=(path,data,token)=>new Request('https://example.test'+path,{method:data===undefined?'GET':'POST',headers:{...(token?{Authorization:'Bearer '+token}:{}),'Content-Type':'application/json'},...(data===undefined?{}:{body:JSON.stringify(data)})});
async function seed(raw,id,sessionId,token){
 const now=Date.now();raw.prepare('INSERT INTO accounts(id,email,display_name,created_at,verified_at) VALUES(?,?,?,?,?)').run(id,id+'@gmail.com',id,now,now);
 raw.prepare('INSERT INTO sessions(id,account_id,token_hash,device_name,created_at,last_seen_at,expires_at) VALUES(?,?,?,?,?,?,?)').run(sessionId,id,await sha(token),'phone',now,now,now+1000000);
 raw.prepare('INSERT INTO google_identities(google_sub,account_id,totp_cipher) VALUES(?,?,?)').run('google-'+id,id,'test-fixture-not-used');
}
test('device and recovery code formats, password salt and pepper',async()=>{
 for(let i=0;i<30;i++){assert.match(newDeviceCode(),/^\d{6}$/);assert.match(newRecoveryCode(),/^[A-F0-9]{4}(?:-[A-F0-9]{4}){7}$/)}
 const e=envFor(null),p='a strong passphrase',s='01'.repeat(16),h=await passwordDigest(e,p,s);
 assert.equal(h,await passwordDigest(e,p,s));assert.notEqual(h,await passwordDigest(e,p,'02'.repeat(16)));assert.notEqual(h,await passwordDigest({...e,TOTP_ENCRYPTION_KEY:'cd'.repeat(32)},p,s));await assert.rejects(passwordDigest(e,'short',s));
});
test('session and security requests are account scoped',async()=>{
 const {raw,db}=makeDB(),env=envFor(db),a='aa'.repeat(32),b='bb'.repeat(32),sidA=crypto.randomUUID(),sidB=crypto.randomUUID();
 await seed(raw,'alice',sidA,a);await seed(raw,'bob',sidB,b);
 const me=await worker.fetch(request('/v2/sessions',undefined,a),env);assert.equal(me.status,200);assert.equal((await me.json()).sessions[0].id,sidA);
 assert.equal((await worker.fetch(new Request('https://example.test/v2/sessions/'+sidB,{method:'DELETE',headers:{Authorization:'Bearer '+a}}),env)).status,404);
 assert.equal((await worker.fetch(request('/v2/security'),env)).status,401);
 const status=await worker.fetch(request('/v2/security',undefined,a),env);assert.equal(status.status,200);assert.equal((await status.json()).password_enabled,false);
 assert.equal((await worker.fetch(request('/v2/security/device-deny',{id:'00'.repeat(32)},a),env)).status,404);
 assert.deepEqual(await (await worker.fetch(request('/v2/security/device-requests',undefined,a),env)).json(),{requests:[]});
 raw.close();
});
test('recovery login requires password and recovery codes are one-use',async()=>{
 const {raw,db}=makeDB(),env=envFor(db),sid=crypto.randomUUID(),tok='aa'.repeat(32),id='alice';
 await seed(raw,id,sid,tok);const p='a strong passphrase',salt='01'.repeat(16),digest=await passwordDigest(env,p,salt);
 raw.prepare('UPDATE accounts SET password_hash=?,password_salt=?,password_iterations=? WHERE id=?').run(digest,salt,100000,id);
 const code=newRecoveryCode(),rh=await sha('peyvanik-recovery:'+code.replace(/-/g,'').toLowerCase()),now=Date.now();
 raw.prepare('INSERT INTO recovery_codes(account_id,code_hash,created_at) VALUES(?,?,?)').run(id,rh,now);
 async function ticket(){const t=Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString('hex');raw.prepare("INSERT INTO totp_challenges(ticket_hash,google_sub,email,display_name,device_name,mode,account_id,created_at,expires_at) VALUES(?,?,?,?,?,'login',?,?,?)").run(await sha(t),'google-'+id,id+'@gmail.com',id,'PC',id,now,now+300000);return t}
 const t=await ticket();
 assert.equal((await auth(request('/v2/auth/recovery',{ticket:t,code}),env)).status,401);
 const ok=await auth(request('/v2/auth/recovery',{ticket:t,code,password:p}),env);assert.equal(ok.status,201);assert.match((await ok.json()).token,/^[a-f0-9]{64}$/);
 assert.equal((await auth(request('/v2/auth/recovery',{ticket:await ticket(),code,password:p}),env)).status,401);
 raw.close();
});
