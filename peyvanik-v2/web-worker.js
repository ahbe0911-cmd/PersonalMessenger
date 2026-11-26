import assets from './web-assets.js';

const COOKIE='__Host-peyvanik';
const tokenPattern=/^[a-f0-9]{64}$/;
const securityHeaders={'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','X-Frame-Options':'DENY','Permissions-Policy':'camera=(), microphone=(), geolocation=()','Content-Security-Policy':"default-src 'self'; script-src 'self' https://accounts.google.com/gsi/client; style-src 'self' https://accounts.google.com/gsi/style; frame-src https://accounts.google.com/gsi/; connect-src 'self' https://accounts.google.com/gsi/; img-src 'self' data: https://*.gstatic.com https://*.googleusercontent.com; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",'Cross-Origin-Opener-Policy':'same-origin-allow-popups'};
const json=(v,status=200,headers={})=>Response.json(v,{status,headers:{...securityHeaders,...headers}});
const cookie=(t,age)=>COOKIE+'='+t+'; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age='+age;
function session(req){const parts=(req.headers.get('Cookie')||'').split(';').map(x=>x.trim());const matches=parts.filter(x=>x.startsWith(COOKIE+'='));if(matches.length!==1)return null;const t=matches[0].slice(COOKIE.length+1);return tokenPattern.test(t)?t:null}
function allowed(path){return /^\/v2\/(?:auth\/(?:config|nonce|google|totp|device|recovery|key-config|key-login|key-register)|me|sessions(?:\/[a-f0-9-]{36})?|security(?:\/(?:device-requests|device-deny|password|recovery|revoke-others))?|profile|users\/search|blocks(?:\/[a-f0-9-]{36})?|conversations(?:\/direct|\/[a-f0-9-]{36}\/(?:sync|messages(?:\/[a-f0-9-]{36})?))?|cloud(?:\/(?:usage|pending|folders(?:\/[a-f0-9-]{36})?|notes|files|[a-f0-9-]{36}(?:\/download)?))?)$/.test(path)}
async function upstream(env,path,method,token,body,contentType,ip){const headers=new Headers();if(token)headers.set('Authorization','Bearer '+token);if(contentType)headers.set('Content-Type',contentType);if(ip)headers.set('CF-Connecting-IP',ip);return env.API.fetch(new Request('https://peyvanik-api.internal'+path,{method,headers,...(body?{body}:{} )}))}
export default{async fetch(req,env){
 try{const u=new URL(req.url),p=u.pathname,m=req.method,origin=req.headers.get('Origin'),site=req.headers.get('Sec-Fetch-Site');
 if(p==='/health'&&m==='GET'){const r=await upstream(env,'/health','GET',null,null,null,null);const b=await r.json();const config=await upstream(env,'/v2/auth/key-config','GET',null,null,null,null);const c=await config.json();return json({ok:r.ok&&b.ok===true&&config.ok,service:'peyvanik-web',backend:b.service==='peyvanik'?'connected':'unavailable',registration:c.enabled?'open':'closed'},r.ok&&b.ok&&config.ok?200:503)}
 if(p==='/keymaker/issue'){
  // Same public signup limits as the normal registration route, no session creation or cookie handling.
  if(m!=='POST')return json({error:'METHOD_NOT_ALLOWED'},405);
  if(req.headers.get('X-Peyvanik-Keymaker')!=='1'||(origin&&origin!==u.origin)||(site&&site!=='same-origin')||(!origin&&site))return json({error:'FORBIDDEN'},403);
  const r=await upstream(env,'/v2/auth/key-issue','POST',null,req.body,req.headers.get('Content-Type'),req.headers.get('CF-Connecting-IP'));
  const v=await r.json();if(r.ok){if(!/^pk_[a-f0-9]{64}$/.test(v.access_key)||!v.account)return json({error:'ISSUE_FAILED'},502);return json({account:v.account,access_key:v.access_key},r.status)}
  return json({error:v.error||'ISSUE_FAILED'},r.status);
 }
 if(!p.startsWith('/api/')){
  if(!['GET','HEAD'].includes(m))return json({error:'METHOD_NOT_ALLOWED'},405);
  if(!['/','/index.html','/app.js','/style.css','/icon.svg','/keymaker','/keymaker.js'].includes(p))return json({error:'NOT_FOUND'},404);
  const a=assets[p==='/'?'/index.html':p==='/keymaker'?'/keymaker.html':p];if(!a)return json({error:'NOT_FOUND'},404);return new Response(m==='HEAD'?null:a.body,{headers:{...securityHeaders,'Content-Type':a.type}})
 }
 // Require an explicit same-origin fetch marker; never forward browser-supplied bearer tokens.
 if(req.headers.get('X-Peyvanik-Request')!=='1'||(site&&site!=='same-origin')||(origin&&origin!==u.origin)||(!['GET','HEAD'].includes(m)&&origin!==u.origin))return json({error:'FORBIDDEN'},403);
 const t=session(req),path=p.slice(4),ip=req.headers.get('CF-Connecting-IP');
 if(path==='/logout'&&m==='POST'){
  if(t){const r=await upstream(env,'/v2/sessions','GET',t,null,null,ip);if(r.ok){const s=await r.json();if(!/^[a-f0-9-]{36}$/.test(s.current))return json({error:'LOGOUT_FAILED'},502);const d=await upstream(env,'/v2/sessions/'+s.current,'DELETE',t,null,null,ip);if(!d.ok&&d.status!==401&&d.status!==404)return json({error:'LOGOUT_FAILED'},503)}else if(r.status!==401)return json({error:'LOGOUT_FAILED'},503)}
  return json({ok:true},200,{'Set-Cookie':cookie('',0)})
 }
 if(!allowed(path))return json({error:'NOT_FOUND'},404);
 if(!path.startsWith('/v2/auth/')&&!t)return json({error:'UNAUTHORIZED'},401);
 const r=await upstream(env,path+u.search,m,t,['GET','HEAD'].includes(m)?null:req.body,req.headers.get('Content-Type'),ip);
 if(path.startsWith('/v2/auth/')){
  const v=await r.json();if(Object.hasOwn(v,'token')){if(!r.ok||!tokenPattern.test(v.token))return json({error:'AUTH_UNAVAILABLE'},502);const {token,...safe}=v;return json(safe,r.status,{'Set-Cookie':cookie(token,30*86400)})}return json(v,r.status)
 }
 const h=new Headers(securityHeaders);for(const k of ['Content-Type','Content-Disposition','X-Media-Type']){const v=r.headers.get(k);if(v)h.set(k,v)}if(r.status===401)h.set('Set-Cookie',cookie('',0));return new Response(r.body,{status:r.status,headers:h});
 }catch{return json({error:'SERVICE_UNAVAILABLE'},503)}
}};
