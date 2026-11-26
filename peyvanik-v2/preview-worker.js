// Public visual preview only. No connection to the private or public account database.
import assets from './preview-assets.js';
export default {async fetch(request){
 const u=new URL(request.url);
 const headers={'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','X-Frame-Options':'DENY','Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"};
 if(request.method==='GET'&&u.pathname==='/health')return Response.json({ok:true,service:'peyvanik-preview',registration:'closed',backend:'not_connected'},{headers});
 if(request.method==='GET'&&u.pathname==='/api/v2/auth/config')return Response.json({enabled:false,manual_setup:true,method:'google_totp'},{headers});
 if(u.pathname.startsWith('/api/'))return Response.json({error:'REGISTRATION_NOT_OPEN'},{status:503,headers});
 if(!['GET','HEAD'].includes(request.method))return new Response('Method not allowed',{status:405,headers});
 const path=u.pathname==='/'?'/index.html':u.pathname,a=assets[path];
 if(!Object.hasOwn(assets,path)||!a)return new Response('Not found',{status:404,headers});
 return new Response(request.method==='HEAD'?null:a.body,{headers:{...headers,'Content-Type':a.type}});
}};
