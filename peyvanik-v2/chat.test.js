
import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import worker from './worker.js';
async function fixture(){
 const raw=new DatabaseSync(':memory:');raw.exec('PRAGMA foreign_keys=ON');
 for(const p of ['schema.sql','002-auth.sql','003-security.sql','004-chat.sql'])raw.exec(readFileSync(new URL(p,import.meta.url),'utf8'));
 const db={prepare(sql){const q=args=>({first:async()=>raw.prepare(sql).get(...args)??null,all:async()=>({results:raw.prepare(sql).all(...args)}),run:async()=>({meta:{changes:Number(raw.prepare(sql).run(...args).changes)}})});return {...q([]),bind:(...args)=>q(args)}},async batch(items){raw.exec('BEGIN');try{const r=[];for(const i of items)r.push(await i.run());raw.exec('COMMIT');return r}catch(e){raw.exec('ROLLBACK');throw e}}};
 const users={};for(const [i,name] of ['alice','bob','carol'].entries()){const id=crypto.randomUUID(),tok=(i+1).toString(16).repeat(64),now=Date.now(),hash=Buffer.from(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(tok))).toString('hex');raw.prepare('INSERT INTO accounts(id,email,username,display_name,created_at,verified_at) VALUES(?,?,?,?,?,?)').run(id,name+'@gmail.com',name,name,now,now);raw.prepare('INSERT INTO sessions(id,account_id,token_hash,device_name,created_at,last_seen_at,expires_at) VALUES(?,?,?,?,?,?,?)').run(crypto.randomUUID(),id,hash,'test',now,now,now+1000000);users[name]={id,tok}}
 async function call(user,method,path,data){const r=await worker.fetch(new Request('https://test.invalid'+path,{method,headers:{...(user?{Authorization:'Bearer '+users[user].tok}:{}),'Content-Type':'application/json'},...(data!==undefined?{body:JSON.stringify(data)}:{})}),{DB:db});return {status:r.status,data:await r.json()}}
 return {raw,users,call};
}
test('profile uniqueness, exact search, no email exposure',async()=>{
 const {raw,users,call}=await fixture();
 assert.equal((await call(null,'GET','/v2/users/search?username=bob')).status,401);
 const s=await call('alice','GET','/v2/users/search?username=BOB');assert.equal(s.status,200);assert.deepEqual(s.data.user,{id:users.bob.id,username:'bob',display_name:'bob'});
 assert.equal((await call('alice','PUT','/v2/profile',{username:'BOB',display_name:'Alice'})).status,409);
 assert.equal((await call('alice','PUT','/v2/profile',{username:'alice_new',display_name:'Alice'})).status,200);
 assert.equal((await call('bob','GET','/v2/users/search?username=alice_new')).data.user.id,users.alice.id);raw.close();
});
test('direct pair deduplication, idempotent send, cross-account denial, two-sided delete and sync',async()=>{
 const {raw,users,call}=await fixture();
 const a=await call('alice','POST','/v2/conversations/direct',{user_id:users.bob.id});assert.equal(a.status,200);const cid=a.data.conversation.id;
 const b=await call('bob','POST','/v2/conversations/direct',{user_id:users.alice.id});assert.equal(b.data.conversation.id,cid);
 const list=await call('alice','GET','/v2/conversations');assert.equal(list.data.conversations.length,1);assert.equal(list.data.conversations[0].peer_id,users.bob.id);
 assert.deepEqual((await call('carol','GET','/v2/conversations')).data.conversations,[]);
 const root='/v2/conversations/'+cid,id=crypto.randomUUID();
 assert.equal((await call('alice','POST',root+'/messages',{id,text:'hello'})).status,201);
 assert.equal((await call('alice','POST',root+'/messages',{id,text:'hello'})).status,200);
 assert.equal((await call('alice','POST',root+'/messages',{id,text:'changed'})).status,409);
 assert.equal((await call('carol','GET',root+'/messages')).status,404);
 assert.equal((await call('carol','POST',root+'/messages',{id:crypto.randomUUID(),text:'intrusion'})).status,404);
 assert.equal((await call('carol','DELETE',root+'/messages/'+id)).status,404);
 const s=await call('bob','GET',root+'/sync?after=0');assert.equal(s.data.events[0].body,'hello');assert.equal(s.data.events.length,1);
 assert.equal((await call('bob','DELETE',root+'/messages/'+id)).status,200);
 assert.equal((await call('bob','DELETE',root+'/messages/'+id)).status,200);
 const del=await call('alice','GET',root+'/sync?after='+s.data.cursor);assert.equal(del.data.events.length,1);assert.equal(del.data.events[0].body,null);assert.ok(del.data.events[0].deleted_at);
 assert.equal((await call('alice','POST',root+'/messages',{id,text:'hello'})).status,410);raw.close();
});
test('block prevents discovery and sending in either direction, preserves history, unblock restores',async()=>{
 const {raw,users,call}=await fixture();
 const cid=(await call('alice','POST','/v2/conversations/direct',{user_id:users.bob.id})).data.conversation.id,root='/v2/conversations/'+cid;
 assert.equal((await call('alice','PUT','/v2/blocks/'+users.bob.id)).status,200);
 assert.equal((await call('bob','GET','/v2/users/search?username=alice')).data.user,null);
 assert.equal((await call('bob','POST','/v2/conversations/direct',{user_id:users.alice.id})).status,404);
 for(const who of ['alice','bob'])assert.equal((await call(who,'POST',root+'/messages',{id:crypto.randomUUID(),text:'blocked'})).status,403);
 assert.equal((await call('bob','GET',root+'/messages')).status,200);
 assert.equal((await call('alice','DELETE','/v2/blocks/'+users.bob.id)).status,200);
 assert.equal((await call('bob','POST',root+'/messages',{id:crypto.randomUUID(),text:'restored'})).status,201);raw.close();
});
