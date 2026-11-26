import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const web=new URL('./web/',import.meta.url);
const server=createServer(async(req,res)=>{try{const p=new URL(req.url,'http://local').pathname;const file=p==='/'?'index.html':p.slice(1);if(!['index.html','app.js','style.css','icon.svg'].includes(file)){res.writeHead(404);return res.end()}const types={html:'text/html; charset=utf-8',js:'text/javascript; charset=utf-8',css:'text/css; charset=utf-8',svg:'image/svg+xml'};res.setHeader('Content-Type',types[file.split('.').at(-1)]);res.end(await readFile(new URL(file,web)))}catch{res.writeHead(500);res.end()}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({headless:true});
const account={id:'10000000-0000-4000-8000-000000000001',display_name:'کاربر آزمایشی',username:'demo'},cid='20000000-0000-4000-8000-000000000001',peer='30000000-0000-4000-8000-000000000001';
try{
for(const width of [320,390,430,1000]){
 const page=await browser.newPage({viewport:{width,height:width===320?640:800},isMobile:width<760,deviceScaleFactor:1,hasTouch:width<760});
 const errors=[],notes=[];let logged=false;page.on('pageerror',e=>errors.push(String(e)));
 await page.route('**/api/**',async route=>{const req=route.request(),p=new URL(req.url()).pathname.slice(4),method=req.method();let json={},status=200;
 if(p==='/v2/me'){if(logged)json={account};else{json={error:'UNAUTHORIZED'};status=401}}
 else if(p==='/v2/auth/key-config')json={enabled:true};
 else if(p==='/v2/auth/key-login'){assert.equal(req.postDataJSON().key,'pk_'+'a'.repeat(64));logged=true;json={account};status=201}
 else if(p==='/v2/conversations')json={conversations:[{id:cid,peer_id:peer,display_name:'دوست آزمایشی',username:'friend',latest_seq:0}]};
 else if(p.endsWith('/messages')&&method==='GET')json={messages:[{id:'40000000-0000-4000-8000-000000000001',sender_id:peer,body:'سلام',created_at:1700000000000}],next_before:null};
 else if(p.endsWith('/sync'))json={events:[],cursor:0,has_more:false};
 else if(p==='/v2/cloud/usage')json={used_bytes:0,quota_bytes:52428800};
 else if(p==='/v2/cloud/folders')json={folders:[]};
 else if(p==='/v2/cloud/notes'){const d=req.postDataJSON();assert.equal(d.text,'یادداشت آزمایشی');assert.equal(d.folder_id,null);notes.push({id:'50000000-0000-4000-8000-000000000001',kind:'note',name:d.name,body:d.text,size:20,created_at:1700000000000});json={item:notes.at(-1)};status=201}
 else if(p==='/v2/cloud')json={items:notes,next_before:null};
 else if(p==='/v2/security/device-requests')json={requests:[]};
 else{json={error:'MOCK_UNHANDLED'};status=404}
 await route.fulfill({status,json});
 });
 await page.goto('http://127.0.0.1:'+server.address().port);
 await page.locator('#key-login').waitFor({state:'visible'});
 await page.waitForFunction(()=>[...document.fonts].some(f=>f.family==='Vazirmatn'&&f.status==='loaded'));
 const fits=()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1&&document.body.scrollWidth<=innerWidth+1);
 assert(await fits(),'login horizontal overflow at '+width);
 await page.locator('#register-tab').click();assert(await page.locator('#key-register').isVisible());assert(!(await page.locator('#key-login').isVisible()));assert(await fits(),'register overflow at '+width);
 const bottom=await page.locator('#key-register button').boundingBox();assert(bottom.y+bottom.height<= (width===320?640:800)+1,'register button clipped at '+width);
 await page.locator('#login-tab').click();await page.locator('#access-key').fill('pk_'+'a'.repeat(64));await page.locator('#key-login button').click();await page.locator('.conversation').first().waitFor({state:'visible'});
 assert(!(await page.locator('.chat-panel').isVisible()));await page.locator('.conversation').first().click();await page.locator('.bubble').first().waitFor({state:'visible'});assert(await fits(),'chat overflow at '+width);
 if(width<760){assert(!(await page.locator('.chat-list').isVisible()));assert(!(await page.locator('.bottom-nav').isVisible()));await page.locator('#chat-back').click();assert(await page.locator('.chat-list').isVisible())}
 await page.locator('nav [data-view=cloud]').click();await page.locator('#cloud-message').waitFor({state:'visible'});assert(!(await page.locator('#cloud-tools').isVisible()));await page.locator('#cloud-message').fill('یادداشت آزمایشی');await page.locator('#add-note').click();await page.locator('.saved-bubble').first().waitFor({state:'visible'});assert.equal(notes.length,1);assert(await fits(),'cloud overflow at '+width);assert.deepEqual(errors,[]);
 console.log('PASS '+width+'px login/register/chat/cloud and Vazirmatn');await page.close();
}
}finally{await browser.close();await new Promise(r=>server.close(r))}
