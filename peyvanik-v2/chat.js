
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const USERNAME=/^[a-z][a-z0-9_]{2,31}$/;
const blockedSql='EXISTS(SELECT 1 FROM user_blocks b WHERE (b.blocker_id=? AND b.blocked_id=?) OR (b.blocker_id=? AND b.blocked_id=?))';
const safeUser=x=>typeof x==='string'&&USERNAME.test(x.toLowerCase())?x.toLowerCase():null;
function numberParam(u,key,def,max,fail){const v=u.searchParams.get(key);if(v===null)return def;if(!/^\d{1,15}$/.test(v)||!Number.isSafeInteger(Number(v))||Number(v)>max)fail(400,'INVALID_CURSOR');return Number(v)}
async function rate(db,owner,now,fail){
 const b=Math.floor(now/60000);
 const r=await db.prepare('INSERT INTO chat_rate(owner_id,bucket,hits) VALUES(?,?,1) ON CONFLICT(owner_id,bucket) DO UPDATE SET hits=hits+1 WHERE hits<60 RETURNING hits').bind(owner,b).first();
 if(!r)fail(429,'TOO_MANY_REQUESTS');
}
async function directId(low,high){
 const bytes=new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode('peyvanik-direct:'+low+':'+high))).slice(0,16);
 bytes[6]=(bytes[6]&15)|128;bytes[8]=(bytes[8]&63)|128;
 const h=Array.from(bytes,x=>x.toString(16).padStart(2,'0')).join('');
 return h.slice(0,8)+'-'+h.slice(8,12)+'-'+h.slice(12,16)+'-'+h.slice(16,20)+'-'+h.slice(20);
}
export async function chat(req,db,owner,{json,fail,body}){
 const u=new URL(req.url),p=u.pathname,m=req.method,now=Date.now();
 if(m!=='GET')await rate(db,owner,now,fail);
 if(p==='/v2/profile'&&m==='PUT'){
  const b=await body(req);const username=safeUser(b?.username),name=typeof b?.display_name==='string'?b.display_name.trim():'';
  if(!username||!name||name.length>80)fail(400,'INVALID_PROFILE');
  try{await db.prepare('UPDATE accounts SET username=?,display_name=? WHERE id=? AND disabled_at IS NULL').bind(username,name,owner).run()}catch(e){if(/UNIQUE/i.test(String(e)))fail(409,'USERNAME_TAKEN');throw e}
  return json({username,display_name:name});
 }
 if(p==='/v2/users/search'&&m==='GET'){
  // Exact handle search, never return email addresses, avoid exposing a complete directory.
  const username=safeUser((u.searchParams.get('username')||'').replace(/^@/,''));
  if(!username)fail(400,'INVALID_USERNAME');
  await rate(db,owner,now,fail);
  const user=await db.prepare('SELECT a.id,a.username,a.display_name FROM accounts a WHERE lower(a.username)=? AND a.id<>? AND a.disabled_at IS NULL AND NOT EXISTS(SELECT 1 FROM user_blocks b WHERE (b.blocker_id=a.id AND b.blocked_id=?) OR (b.blocker_id=? AND b.blocked_id=a.id))').bind(username,owner,owner,owner).first();
  return json({user:user||null});
 }
 if(p==='/v2/blocks'&&m==='GET'){
  const r=await db.prepare('SELECT a.id,a.username,a.display_name,b.created_at FROM user_blocks b JOIN accounts a ON a.id=b.blocked_id WHERE b.blocker_id=? ORDER BY b.created_at DESC LIMIT 200').bind(owner).all();return json({users:r.results});
 }
 let match=/^\/v2\/blocks\/([0-9a-f-]{36})$/.exec(p);
 if(match&&UUID.test(match[1])){
  const target=match[1];if(target===owner)fail(400,'INVALID_TARGET');
  if(m==='PUT'){
   const r=await db.prepare('INSERT INTO user_blocks(blocker_id,blocked_id,created_at) SELECT ?,id,? FROM accounts WHERE id=? ON CONFLICT(blocker_id,blocked_id) DO NOTHING').bind(owner,now,target).run();
   if(!r.meta.changes&&!await db.prepare('SELECT 1 FROM user_blocks WHERE blocker_id=? AND blocked_id=?').bind(owner,target).first())fail(404,'NOT_FOUND');
   return json({ok:true});
  }
  if(m==='DELETE'){await db.prepare('DELETE FROM user_blocks WHERE blocker_id=? AND blocked_id=?').bind(owner,target).run();return json({ok:true})}
 }
 if(p==='/v2/conversations/direct'&&m==='POST'){
  const b=await body(req),target=b?.user_id;
  if(typeof target!=='string'||!UUID.test(target)||target===owner)fail(400,'INVALID_TARGET');
  if(!await db.prepare('SELECT 1 FROM accounts WHERE id=? AND disabled_at IS NULL AND username IS NOT NULL AND NOT '+blockedSql).bind(target,owner,target,target,owner).first())fail(404,'NOT_FOUND');
  const [low,high]=[owner,target].sort(),id=await directId(low,high);
  // All statements are one transaction. Deterministic ID and unique ordered pair prevent duplicate conversations.
  await db.batch([
   db.prepare("INSERT INTO conversations(id,created_at,kind) SELECT ?,?,'direct' WHERE EXISTS(SELECT 1 FROM accounts WHERE id=? AND disabled_at IS NULL) AND NOT "+blockedSql+" ON CONFLICT(id) DO NOTHING").bind(id,now,target,owner,target,target,owner),
   db.prepare('INSERT INTO direct_pairs(account_low,account_high,conversation_id) SELECT ?,?,id FROM conversations WHERE id=? AND kind=? AND NOT '+blockedSql+' ON CONFLICT(account_low,account_high) DO NOTHING').bind(low,high,id,'direct',owner,target,target,owner),
   ...[owner,target].map(member=>db.prepare('INSERT INTO conversation_members(conversation_id,account_id,joined_at) SELECT conversation_id,?,? FROM direct_pairs WHERE account_low=? AND account_high=? AND NOT '+blockedSql+' ON CONFLICT(conversation_id,account_id) DO NOTHING').bind(member,now,low,high,owner,target,target,owner))
  ]);
  const row=await db.prepare('SELECT d.conversation_id AS id FROM direct_pairs d JOIN conversation_members cm ON cm.conversation_id=d.conversation_id AND cm.account_id=? WHERE d.account_low=? AND d.account_high=? AND NOT '+blockedSql).bind(owner,low,high,owner,target,target,owner).first();
  if(!row)fail(404,'NOT_FOUND');return json({conversation:row},200);
 }
 if(p==='/v2/conversations'&&m==='GET'){
  const r=await db.prepare("SELECT c.id,c.kind,c.created_at,a.id AS peer_id,a.username,a.display_name,COALESCE((SELECT MAX(e.seq) FROM chat_events e WHERE e.conversation_id=c.id),0) AS latest_seq FROM conversations c JOIN conversation_members cm ON cm.conversation_id=c.id AND cm.account_id=? JOIN direct_pairs d ON d.conversation_id=c.id JOIN accounts a ON a.id=CASE WHEN d.account_low=? THEN d.account_high ELSE d.account_low END WHERE c.kind='direct' ORDER BY latest_seq DESC,c.created_at DESC LIMIT 100").bind(owner,owner).all();return json({conversations:r.results});
 }
 match=/^\/v2\/conversations\/([0-9a-f-]{36})\/(messages|sync)(?:\/([0-9a-f-]{36}))?$/.exec(p);
 if(!match||!UUID.test(match[1])||(match[3]&&!UUID.test(match[3])))return json({error:'NOT_FOUND'},404);
 const [_,cid,resource,mid]=match;
 const pair=await db.prepare("SELECT CASE WHEN d.account_low=? THEN d.account_high ELSE d.account_low END AS peer FROM direct_pairs d JOIN conversation_members cm ON cm.conversation_id=d.conversation_id AND cm.account_id=? WHERE d.conversation_id=?").bind(owner,owner,cid).first();
 if(!pair)fail(404,'NOT_FOUND');
 const peer=pair.peer;
 if(resource==='sync'&&m==='GET'&&!mid){
  const after=numberParam(u,'after',0,Number.MAX_SAFE_INTEGER,fail);
  const r=await db.prepare('SELECT e.seq,m.id,m.sender_id,CASE WHEN m.deleted_at IS NULL THEN m.body ELSE NULL END AS body,m.created_at,m.deleted_at FROM chat_events e JOIN chat_messages m ON m.id=e.message_id AND m.conversation_id=e.conversation_id WHERE e.conversation_id=? AND e.seq>? ORDER BY e.seq LIMIT 101').bind(cid,after).all();
  const items=r.results.slice(0,100);return json({events:items,cursor:items.at(-1)?.seq??after,has_more:r.results.length>100});
 }
 if(resource==='messages'&&m==='GET'&&!mid){
  const before=numberParam(u,'before',Number.MAX_SAFE_INTEGER,Number.MAX_SAFE_INTEGER,fail);
  const r=await db.prepare('SELECT rowid AS position,id,sender_id,CASE WHEN deleted_at IS NULL THEN body ELSE NULL END AS body,created_at,deleted_at FROM chat_messages WHERE conversation_id=? AND rowid<? ORDER BY rowid DESC LIMIT 101').bind(cid,before).all();
  const items=r.results.slice(0,100);return json({messages:items.reverse(),next_before:r.results.length>100?items[0].position:null});
 }
 if(resource==='messages'&&m==='POST'&&!mid){
  const b=await body(req),id=b?.id,text=b?.text;
  if(typeof id!=='string'||!UUID.test(id)||typeof text!=='string'||!text.trim()||text.length>16000)fail(400,'INVALID_MESSAGE');
  // Client-generated UUID supports safe retry. Check block and account state in the actual write statement.
  const r=await db.prepare("INSERT INTO chat_messages(id,conversation_id,sender_id,body,created_at) SELECT ?,?,?,?,? WHERE EXISTS(SELECT 1 FROM conversation_members WHERE conversation_id=? AND account_id=?) AND EXISTS(SELECT 1 FROM accounts WHERE id=? AND disabled_at IS NULL) AND NOT "+blockedSql+" ON CONFLICT(id) DO NOTHING").bind(id,cid,owner,text,now,cid,owner,peer,owner,peer,peer,owner).run();
  const saved=await db.prepare('SELECT id,sender_id,body,created_at,deleted_at FROM chat_messages WHERE id=? AND conversation_id=? AND sender_id=?').bind(id,cid,owner).first();
  if(!saved){if(await db.prepare('SELECT 1 WHERE '+blockedSql).bind(owner,peer,peer,owner).first())fail(403,'MESSAGE_NOT_ALLOWED');fail(409,'MESSAGE_NOT_SAVED')}
  if(saved.deleted_at!==null)fail(410,'MESSAGE_DELETED');
  if(saved.body!==text)fail(409,'MESSAGE_ID_CONFLICT');
  return json({message:saved},r.meta.changes?201:200);
 }
 if(resource==='messages'&&m==='DELETE'&&mid){
  // Both participants can delete either message in a direct chat; membership is checked above.
  const r=await db.prepare("UPDATE chat_messages SET deleted_at=?,body='' WHERE id=? AND conversation_id=? AND deleted_at IS NULL").bind(now,mid,cid).run();
  if(!r.meta.changes&&!await db.prepare('SELECT 1 FROM chat_messages WHERE id=? AND conversation_id=? AND deleted_at IS NOT NULL').bind(mid,cid).first())fail(404,'NOT_FOUND');
  return json({ok:true});
 }
 return json({error:'NOT_FOUND'},404);
}
