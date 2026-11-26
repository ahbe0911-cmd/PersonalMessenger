
import test from 'node:test';import assert from 'node:assert/strict';import {DatabaseSync} from 'node:sqlite';import {readFileSync} from 'node:fs';import worker from './worker.js';
test('key signup and login isolate accounts, reject invalid keys and revoke other sessions',async()=>{
 const raw=new DatabaseSync(':memory:');raw.exec('PRAGMA foreign_keys=ON');for(const p of ['schema.sql','006-key-auth.sql'])raw.exec(readFileSync(new URL(p,import.meta.url),'utf8'));
 const db={prepare(sql){const q=args=>({first:async()=>raw.prepare(sql).get(...args)??null,all:async()=>({results:raw.prepare(sql).all(...args)}),run:async()=>({meta:{changes:Number(raw.prepare(sql).run(...args).changes)}})});return {...q([]),bind:(...args)=>q(args)}},async batch(items){raw.exec('BEGIN');try{const a=[];for(const i of items)a.push(await i.run());raw.exec('COMMIT');return a}catch(e){raw.exec('ROLLBACK');throw e}}};
 const env={DB:db,KEY_AUTH_OPEN:'true'};
 const call=async(path,data,token,method=data===undefined?'GET':'POST')=>{const r=await worker.fetch(new Request('https://test.invalid'+path,{method,headers:{...(token?{Authorization:'Bearer '+token}:{}),'Content-Type':'application/json'},...(data!==undefined?{body:JSON.stringify(data)}:{})}),env);return {status:r.status,data:await r.json()}};
 const register=async name=>call('/v2/auth/key-register',{username:name,display_name:name,device_name:'phone',accept_key_responsibility:true});
 const a=await register('alice'),b=await register('bob');assert.equal(a.status,201);assert.equal(b.status,201);assert.notEqual(a.data.access_key,b.data.access_key);assert.match(a.data.access_key,/^pk_[a-f0-9]{64}$/);assert.notEqual(raw.prepare('SELECT key_hash FROM key_identities WHERE account_id=?').get(a.data.account.id).key_hash,a.data.access_key);
 assert.equal((await call('/v2/auth/key-login',{key:'wrong',device_name:'pc'})).status,401);
 const pc=await call('/v2/auth/key-login',{key:a.data.access_key,device_name:'pc'});assert.equal(pc.status,201);assert.equal(pc.data.account.id,a.data.account.id);
 assert.equal((await call('/v2/me',undefined,b.data.token)).data.account.id,b.data.account.id);
 assert.equal((await call('/v2/me',undefined,a.data.token)).data.account.email,null);
 assert.equal((await call('/v2/security/revoke-others',{key:b.data.access_key},a.data.token)).status,403);
 assert.equal((await call('/v2/security/revoke-others',{key:a.data.access_key},a.data.token)).status,200);
 assert.equal((await call('/v2/me',undefined,pc.data.token)).status,401);
 assert.equal((await call('/v2/me',undefined,b.data.token)).status,200);
 const issued=await call('/v2/auth/key-issue',{username:'carol',display_name:'Carol',accept_key_responsibility:true});assert.equal(issued.status,201);assert.equal(issued.data.token,undefined);assert.equal(raw.prepare('SELECT count(*) AS n FROM sessions WHERE account_id=?').get(issued.data.account.id).n,0);
 const c=await call('/v2/auth/key-login',{key:issued.data.access_key,device_name:'pc'});assert.equal(c.status,201);assert.equal(c.data.account.id,issued.data.account.id);
 raw.close();
});
