
export const MAX_FILE=20*1024*1024, ACCOUNT_QUOTA=50*1024*1024, PROJECT_QUOTA=300*1024*1024;
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const fields='id,kind,name,body,mime,size,created_at,updated_at,folder_id,favorite';
const categorySQL="CASE WHEN kind='note' THEN 'note' WHEN mime LIKE 'image/%' THEN 'image' WHEN mime LIKE 'audio/%' THEN 'audio' WHEN mime LIKE 'video/%' THEN 'video' WHEN mime='application/pdf' THEN 'pdf' ELSE 'other' END";
const categories=['note','image','audio','video','pdf','other'];
const own=(o,k)=>Object.hasOwn(o,k);
function safeName(s,max,fail){if(typeof s!=='string')fail(400,'INVALID_NAME');const n=s.trim();if(!n||n.length>max||/[\x00-\x1f\x7f]/.test(n))fail(400,'INVALID_NAME');return n}
async function folder(db,owner,id,fail){if(id===null)return null;if(typeof id!=='string'||!UUID.test(id))fail(400,'INVALID_FOLDER');if(!await db.prepare('SELECT 1 FROM cloud_folders WHERE id=? AND owner_id=?').bind(id,owner).first())fail(404,'NOT_FOUND');return id}
async function rate(db,owner,now,fail){
 const r=await db.prepare('INSERT INTO cloud_rate(owner_id,bucket,hits) VALUES(?,?,1) ON CONFLICT(owner_id,bucket) DO UPDATE SET hits=hits+1 WHERE hits<40 RETURNING hits').bind(owner,Math.floor(now/60000)).first();if(!r)fail(429,'TOO_MANY_REQUESTS');
}
function cursor(u,key,def,max,fail){const s=u.searchParams.get(key);if(s===null)return def;if(!/^\d{1,16}$/.test(s)||!Number.isSafeInteger(Number(s))||Number(s)<1||Number(s)>max)fail(400,'INVALID_CURSOR');return Number(s)}
function safeMime(s){const m=(s||'application/octet-stream').split(';')[0].trim().toLowerCase();return /^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/.test(m)&&m.length<=100?m:'application/octet-stream'}
async function remove(env,owner,item,now,fail){
 // A still-running upload must never race deletion. Old pending rows retain their quota until an explicit cleanup.
 if(item.state==='pending'&&item.created_at>now-86400000)fail(409,'UPLOAD_PENDING');
 const r=await env.DB.prepare("UPDATE cloud_items SET state='deleting',updated_at=? WHERE id=? AND owner_id=? AND (state<>'pending' OR created_at<=?)").bind(now,item.id,owner,now-86400000).run();
 if(!r.meta.changes)fail(404,'NOT_FOUND');
 if(item.object_key)await env.MEDIA.delete(item.object_key);
 await env.DB.prepare("DELETE FROM cloud_items WHERE id=? AND owner_id=? AND state='deleting'").bind(item.id,owner).run();
}
export async function cloud(req,env,owner,{json,fail,body,bounded}){
 const db=env.DB,u=new URL(req.url),p=u.pathname,m=req.method,now=Date.now();
 if(m!=='GET')await rate(db,owner,now,fail);
 if(p==='/v2/cloud/usage'&&m==='GET'){
  const r=await db.prepare("SELECT COALESCE(SUM(size),0) AS used_bytes,count(*) AS item_count,COALESCE(SUM(CASE WHEN state<>'ready' THEN size ELSE 0 END),0) AS reserved_bytes FROM cloud_items WHERE owner_id=?").bind(owner).first();
  const cats=await db.prepare("SELECT "+categorySQL+" AS category,count(*) AS count,COALESCE(SUM(size),0) AS bytes FROM cloud_items WHERE owner_id=? AND state='ready' GROUP BY category").bind(owner).all();
  return json({...r,quota_bytes:ACCOUNT_QUOTA,max_file_bytes:MAX_FILE,max_items:2000,categories:cats.results});
 }
 if(p==='/v2/cloud/folders'&&m==='GET'){
  const r=await db.prepare("SELECT f.id,f.name,f.created_at,(SELECT count(*) FROM cloud_items i WHERE i.owner_id=f.owner_id AND i.folder_id=f.id AND i.state='ready') AS item_count FROM cloud_folders f WHERE f.owner_id=? ORDER BY f.name,f.id LIMIT 100").bind(owner).all();return json({folders:r.results});
 }
 if(p==='/v2/cloud/folders'&&m==='POST'){
  const b=await body(req),name=safeName(b?.name,100,fail),id=crypto.randomUUID();
  try{const r=await db.prepare('INSERT INTO cloud_folders(id,owner_id,name,created_at) SELECT ?,?,?,? WHERE (SELECT count(*) FROM cloud_folders WHERE owner_id=?)<100').bind(id,owner,name,now,owner).run();if(!r.meta.changes)fail(507,'FOLDER_LIMIT')}catch(e){if(/UNIQUE/i.test(String(e)))fail(409,'FOLDER_NAME_TAKEN');throw e}
  return json({folder:{id,name,created_at:now}},201);
 }
 let match=/^\/v2\/cloud\/folders\/([0-9a-f-]{36})$/.exec(p);
 if(match&&UUID.test(match[1])){
  const id=await folder(db,owner,match[1],fail);
  if(m==='PATCH'){const b=await body(req),name=safeName(b?.name,100,fail);try{const r=await db.prepare('UPDATE cloud_folders SET name=? WHERE id=? AND owner_id=?').bind(name,id,owner).run();if(!r.meta.changes)fail(404,'NOT_FOUND')}catch(e){if(/UNIQUE/i.test(String(e)))fail(409,'FOLDER_NAME_TAKEN');throw e}return json({ok:true})}
  if(m==='DELETE'){
   // Deleting the folder moves its contents back to root, not into the trash.
   await db.batch([db.prepare('UPDATE cloud_items SET folder_id=NULL,updated_at=? WHERE owner_id=? AND folder_id=?').bind(now,owner,id),db.prepare('DELETE FROM cloud_folders WHERE id=? AND owner_id=?').bind(id,owner)]);return json({ok:true});
  }
 }
 if(p==='/v2/cloud'&&m==='GET'){
  const q=(u.searchParams.get('q')||'').trim();if(q.length>120)fail(400,'INVALID_SEARCH');
  const f=u.searchParams.get('folder'),cat=u.searchParams.get('category'),favorite=u.searchParams.get('favorite');
  if(cat!==null&&!categories.includes(cat))fail(400,'INVALID_CATEGORY');
  if(favorite!==null&&!['0','1'].includes(favorite))fail(400,'INVALID_FAVORITE');
  const before=cursor(u,'before',Number.MAX_SAFE_INTEGER,Number.MAX_SAFE_INTEGER,fail),limit=cursor(u,'limit',50,100,fail);
  const args=[owner,before];let sql="SELECT rowid AS position,"+fields+" FROM cloud_items WHERE owner_id=? AND rowid<? AND state='ready'";
  if(f==='root')sql+=' AND folder_id IS NULL';else if(f!==null){await folder(db,owner,f,fail);sql+=' AND folder_id=?';args.push(f)}
  if(q){sql+=" AND (instr(lower(name),lower(?))>0 OR instr(lower(COALESCE(body,'')),lower(?))>0)";args.push(q,q)}
  if(cat){sql+=' AND '+categorySQL+'=?';args.push(cat)}
  if(favorite!==null){sql+=' AND favorite=?';args.push(Number(favorite))}
  const r=await db.prepare(sql+' ORDER BY rowid DESC LIMIT ?').bind(...args,limit+1).all(),items=r.results.slice(0,limit);
  return json({items,next_before:r.results.length>limit?items.at(-1).position:null});
 }
 if(p==='/v2/cloud/pending'&&m==='GET'){
  const r=await db.prepare("SELECT id,name,size,state,created_at FROM cloud_items WHERE owner_id=? AND state<>'ready' ORDER BY created_at DESC LIMIT 100").bind(owner).all();return json({items:r.results.map(x=>({...x,can_remove:x.state==='deleting'||x.created_at<=now-86400000}))});
 }
 if(p==='/v2/cloud/notes'&&m==='POST'){
  const b=await body(req);if(typeof b?.text!=='string'||!b.text.trim()||b.text.length>16000)fail(400,'INVALID_NOTE');
  const name=b.name===undefined?'یادداشت':safeName(b.name,240,fail),fid=await folder(db,owner,b.folder_id??null,fail),id=crypto.randomUUID(),size=new TextEncoder().encode(b.text).length;
  const r=await db.prepare("INSERT INTO cloud_items(id,owner_id,kind,name,body,size,created_at,updated_at,folder_id) SELECT ?,?,'note',?,?,?,?,?,? WHERE (SELECT count(*) FROM cloud_items WHERE owner_id=?)<2000 AND (SELECT COALESCE(SUM(size),0) FROM cloud_items WHERE owner_id=?)+?<=? AND (SELECT COALESCE(SUM(size),0) FROM cloud_items)+?<=?").bind(id,owner,name,b.text,size,now,now,fid,owner,owner,size,ACCOUNT_QUOTA,size,PROJECT_QUOTA).run();if(!r.meta.changes)fail(507,'QUOTA');
  return json({item:{id,kind:'note',name,body:b.text,size,folder_id:fid,favorite:0,created_at:now,updated_at:now}},201);
 }
 if(p==='/v2/cloud/files'&&m==='POST'){
  const name=safeName(u.searchParams.get('name')||'file',240,fail),mime=safeMime(req.headers.get('Content-Type')),fid=await folder(db,owner,u.searchParams.get('folder'),fail);
  const data=await bounded(req,MAX_FILE);if(!data.length)fail(400,'EMPTY_FILE');
  const id=crypto.randomUUID(),key='cloud/'+owner+'/'+id;
  const r=await db.prepare("INSERT INTO cloud_items(id,owner_id,kind,name,object_key,mime,size,created_at,updated_at,folder_id,state) SELECT ?,?,'file',?,?,?,?,?,?,?,'pending' WHERE (SELECT COALESCE(SUM(size),0) FROM cloud_items WHERE owner_id=?)+?<=? AND (SELECT COALESCE(SUM(size),0) FROM cloud_items)+?<=? AND (SELECT count(*) FROM cloud_items WHERE owner_id=?)<2000").bind(id,owner,name,key,mime,data.length,now,now,fid,owner,data.length,ACCOUNT_QUOTA,data.length,PROJECT_QUOTA,owner).run();
  if(!r.meta.changes)fail(507,'QUOTA');
  try{await env.MEDIA.put(key,data);const done=await db.prepare("UPDATE cloud_items SET state='ready' WHERE id=? AND owner_id=? AND state='pending'").bind(id,owner).run();if(!done.meta.changes)fail(503,'STORAGE_PENDING')}catch{fail(503,'STORAGE_PENDING')}
  return json({item:{id,kind:'file',name,mime,size:data.length,folder_id:fid,favorite:0,created_at:now,updated_at:now}},201);
 }
 match=/^\/v2\/cloud\/([0-9a-f-]{36})(\/download)?$/.exec(p);
 if(match&&UUID.test(match[1])){
  const item=await db.prepare('SELECT * FROM cloud_items WHERE id=? AND owner_id=?').bind(match[1],owner).first();if(!item)fail(404,'NOT_FOUND');
  if(m==='GET'&&match[2]){
   if(item.kind!=='file'||item.state!=='ready')fail(404,'NOT_FOUND');const data=await env.MEDIA.get(item.object_key,{type:'arrayBuffer'});if(!data)fail(503,'MEDIA_PENDING');
   return new Response(data,{headers:{'Content-Type':'application/octet-stream','Content-Disposition':"attachment; filename*=UTF-8''"+encodeURIComponent(item.name).replace(/['()]/g,c=>'%'+c.charCodeAt(0).toString(16)),'X-Media-Type':item.mime,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
  }
  if(match[2])return json({error:'NOT_FOUND'},404);
  if(m==='DELETE'){await remove(env,owner,item,now,fail);return json({ok:true})}
  if(item.state!=='ready')fail(404,'NOT_FOUND');
  if(m==='GET')return json({item:await db.prepare('SELECT '+fields+' FROM cloud_items WHERE id=? AND owner_id=? AND state=?').bind(item.id,owner,'ready').first()});
  if(m==='PATCH'){
   const b=await body(req);if(!b||typeof b!=='object'||Array.isArray(b)||!Object.keys(b).length||Object.keys(b).some(k=>!['name','folder_id','favorite'].includes(k)))fail(400,'INVALID_UPDATE');
   const name=own(b,'name')?safeName(b.name,240,fail):item.name,fid=own(b,'folder_id')?await folder(db,owner,b.folder_id,fail):item.folder_id;
   if(own(b,'favorite')&&typeof b.favorite!=='boolean')fail(400,'INVALID_FAVORITE');
   const favorite=own(b,'favorite')?Number(b.favorite):item.favorite;
   const r=await db.prepare("UPDATE cloud_items SET name=?,folder_id=?,favorite=?,updated_at=? WHERE id=? AND owner_id=? AND state='ready'").bind(name,fid,favorite,now,item.id,owner).run();if(!r.meta.changes)fail(404,'NOT_FOUND');return json({ok:true});
  }
 }
 return json({error:'NOT_FOUND'},404);
}
