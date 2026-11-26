
import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import worker from './worker.js';
import {MAX_FILE,ACCOUNT_QUOTA} from './cloud.js';
async function fixture(){
 const raw=new DatabaseSync(':memory:');raw.exec('PRAGMA foreign_keys=ON');
 for(const p of ['schema.sql','002-auth.sql','003-security.sql','004-chat.sql','005-cloud.sql'])raw.exec(readFileSync(new URL(p,import.meta.url),'utf8'));
 const db={prepare(sql){const q=args=>({first:async()=>raw.prepare(sql).get(...args)??null,all:async()=>({results:raw.prepare(sql).all(...args)}),run:async()=>({meta:{changes:Number(raw.prepare(sql).run(...args).changes)}})});return {...q([]),bind:(...args)=>q(args)}},async batch(items){raw.exec('BEGIN');try{const r=[];for(const i of items)r.push(await i.run());raw.exec('COMMIT');return r}catch(e){raw.exec('ROLLBACK');throw e}}};
 const objects=new Map(),MEDIA={put:async(k,v)=>{objects.set(k,v.slice())},get:async k=>{const b=objects.get(k);return b?b.buffer.slice(b.byteOffset,b.byteOffset+b.byteLength):null},delete:async k=>objects.delete(k)};
 const users={};for(const [i,name] of ['alice','bob'].entries()){const id=crypto.randomUUID(),tok=(i+1).toString(16).repeat(64),now=Date.now(),hash=Buffer.from(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(tok))).toString('hex');raw.prepare('INSERT INTO accounts(id,email,username,display_name,created_at,verified_at) VALUES(?,?,?,?,?,?)').run(id,name+'@gmail.com',name,name,now,now);raw.prepare('INSERT INTO sessions(id,account_id,token_hash,device_name,created_at,last_seen_at,expires_at) VALUES(?,?,?,?,?,?,?)').run(crypto.randomUUID(),id,hash,'test',now,now,now+1000000);users[name]={id,tok}}
 async function call(user,method,path,data,extra={}){const binary=data instanceof Uint8Array;const r=await worker.fetch(new Request('https://test.invalid'+path,{method,headers:{...(user?{Authorization:'Bearer '+users[user].tok}:{}),'Content-Type':binary?'application/pdf':'application/json',...extra},...(data!==undefined?{body:binary?data:JSON.stringify(data)}:{})}),{DB:db,MEDIA});const d=r.headers.get('content-type')?.includes('application/json')?await r.json():new Uint8Array(await r.arrayBuffer());return {status:r.status,data:d,headers:r.headers}}
 return {raw,users,objects,MEDIA,call};
}
test('folder ownership, search, favorites, moving and deletion preserve contents',async()=>{
 const {raw,call}=await fixture(),root='/v2/cloud';
 assert.equal((await call(null,'GET',root)).status,401);
 const f=await call('alice','POST',root+'/folders',{name:'مدارک'});assert.equal(f.status,201);const fid=f.data.folder.id;
 assert.equal((await call('bob','POST',root+'/notes',{text:'forged',folder_id:fid})).status,404);
 const n=await call('alice','POST',root+'/notes',{text:'یادداشت مهم',name:'کار',folder_id:fid});assert.equal(n.status,201);const id=n.data.item.id;
 assert.equal((await call('bob','GET',root+'/'+id)).status,404);
 assert.equal((await call('bob','PATCH',root+'/'+id,{favorite:true})).status,404);
 assert.equal((await call('bob','DELETE',root+'/'+id)).status,404);
 assert.equal((await call('alice','PATCH',root+'/'+id,{name:'New note',favorite:true})).status,200);
 assert.equal((await call('alice','GET',root+'?folder='+fid+'&q=NEW&favorite=1&category=note')).data.items[0].id,id);
 assert.equal((await call('alice','GET',root+'?folder=root')).data.items.length,0);
 assert.equal((await call('bob','GET',root+'?folder='+fid)).status,404);
 assert.equal((await call('alice','DELETE',root+'/folders/'+fid)).status,200);
 assert.equal((await call('alice','GET',root+'?folder=root')).data.items[0].id,id);
 assert.equal((await call('alice','GET',root+'/usage')).data.used_bytes,new TextEncoder().encode('یادداشت مهم').length);
 raw.close();
});
test('private binary roundtrip, category, quota and deletion',async()=>{
 const {raw,call,objects,users}=await fixture(),root='/v2/cloud',bytes=new Uint8Array([37,80,68,70,10,1,2,3]);
 const r=await call('alice','POST',root+'/files?name=scan.pdf',bytes);assert.equal(r.status,201);const id=r.data.item.id;assert.equal(objects.size,1);
 assert.equal((await call('bob','GET',root+'/'+id+'/download')).status,404);
 const down=await call('alice','GET',root+'/'+id+'/download');assert.equal(down.status,200);assert.deepEqual(down.data,bytes);assert.match(down.headers.get('content-disposition'),/attachment/);
 assert.equal((await call('alice','GET',root+'?category=pdf')).data.items[0].id,id);
 assert.equal((await call('alice','GET',root+'/usage')).data.used_bytes,bytes.length);
 assert.equal((await call('alice','POST',root+'/files?name=large.pdf',new Uint8Array(1),{'Content-Length':String(MAX_FILE+1)})).status,413);
 raw.prepare("INSERT INTO cloud_items(id,owner_id,kind,name,body,size,created_at) VALUES(?,?,'note','quota','x',?,?)").run(crypto.randomUUID(),users.alice.id,ACCOUNT_QUOTA-bytes.length,Date.now());
 assert.equal((await call('alice','POST',root+'/notes',{text:'x'})).status,507);
 assert.equal((await call('alice','DELETE',root+'/'+id)).status,200);assert.equal(objects.size,0);
 assert.equal((await call('alice','GET',root+'/'+id+'/download')).status,404);raw.close();
});
test('pagination and pending storage failure remain isolated',async()=>{
 const {raw,call,MEDIA,users}=await fixture(),root='/v2/cloud';
 for(let i=0;i<3;i++)assert.equal((await call('alice','POST',root+'/notes',{text:'note '+i})).status,201);
 const page=await call('alice','GET',root+'?limit=2');assert.equal(page.data.items.length,2);
 const next=await call('alice','GET',root+'?limit=2&before='+page.data.next_before);assert.equal(next.data.items.length,1);
 MEDIA.put=async()=>{throw Error('mock storage unavailable')};
 assert.equal((await call('alice','POST',root+'/files?name=x.pdf',new Uint8Array([1]))).status,503);
 const pending=await call('alice','GET',root+'/pending');assert.equal(pending.data.items.length,1);const id=pending.data.items[0].id;
 assert.equal((await call('alice','DELETE',root+'/'+id)).status,409);
 assert.deepEqual((await call('bob','GET',root+'/pending')).data.items,[]);
 raw.prepare('UPDATE cloud_items SET created_at=? WHERE id=? AND owner_id=?').run(Date.now()-86400001,id,users.alice.id);
 assert.equal((await call('alice','DELETE',root+'/'+id)).status,200);raw.close();
});
