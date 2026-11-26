import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import webWorker from './web-worker.js';
const web=new URL('./web/',import.meta.url);
const server=createServer(async(req,res)=>{try{const r=await webWorker.fetch(new Request('http://127.0.0.1'+req.url),{});res.writeHead(r.status,Object.fromEntries(r.headers));res.end(Buffer.from(await r.arrayBuffer()))}catch{res.writeHead(500);res.end()}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({headless:true});
function samplePdf(){let out='%PDF-1.4\n',offsets=[0];const objects=['<< /Type /Catalog /Pages 2 0 R >>','<< /Type /Pages /Kids [3 0 R] /Count 1 >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << >> /Contents 4 0 R >>','<< /Length 12 >>\nstream\n0 0 50 50 re\nendstream'];objects.forEach((o,i)=>{offsets.push(out.length);out+=(i+1)+' 0 obj\n'+o+'\nendobj\n'});const xref=out.length;out+='xref\n0 5\n0000000000 65535 f \n'+offsets.slice(1).map(n=>String(n).padStart(10,'0')+' 00000 n \n').join('')+'trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n'+xref+'\n%%EOF\n';return out}
const account={id:'10000000-0000-4000-8000-000000000001',display_name:'کاربر آزمایشی',username:'demo'},cid='20000000-0000-4000-8000-000000000001',peer='30000000-0000-4000-8000-000000000001';
try{
for(const width of [320,390,430,1000]){
 const page=await browser.newPage({viewport:{width,height:width===320?640:800},isMobile:width<760,deviceScaleFactor:1,hasTouch:width<760});
 const errors=[],notes=[];let logged=false,deleted=false,cloudDeleted=false,failedHistory=false;const cid2='20000000-0000-4000-8000-000000000002',peer2='30000000-0000-4000-8000-000000000002';const fileId='60000000-0000-4000-8000-000000000001',pdfId='60000000-0000-4000-8000-000000000002';page.on('pageerror',e=>errors.push(String(e)));
 await page.route('**/api/**',async route=>{const req=route.request(),p=new URL(req.url()).pathname.slice(4),method=req.method();let json={},status=200;
 if(p==='/v2/me'){if(logged)json={account};else{json={error:'UNAUTHORIZED'};status=401}}
 else if(p==='/v2/auth/key-config')json={enabled:true};
 else if(p==='/v2/auth/key-login'){assert.equal(req.postDataJSON().key,'pk_'+'a'.repeat(64));logged=true;json={account};status=201}
 else if(p==='/v2/conversations')json={conversations:[{id:cid,peer_id:peer,display_name:'دوست آزمایشی',username:'friend',latest_seq:0},{id:cid2,peer_id:peer2,display_name:'دوست دوم',username:'friend2',latest_seq:0}]};
 else if(p.endsWith('/messages')&&method==='GET'){if(p.includes(cid2)&&!failedHistory){failedHistory=true;status=503;json={error:'SERVICE_UNAVAILABLE'}}else json={messages:[{id:'40000000-0000-4000-8000-000000000001',sender_id:peer,body:'سلام',created_at:1700000000000}],next_before:null}}
 else if(method==='DELETE'&&p.includes('/messages/')){await new Promise(r=>setTimeout(r,650));deleted=true;json={ok:true}}
 else if(p.endsWith('/sync'))json={events:[],cursor:0,has_more:false};
 else if(p==='/v2/cloud/usage')json={used_bytes:0,quota_bytes:52428800};
 else if(p==='/v2/cloud/folders')json={folders:[]};
 else if(p==='/v2/cloud/notes'){const d=req.postDataJSON();assert.equal(d.text,'یادداشت آزمایشی');assert.equal(d.folder_id,null);notes.push({id:'50000000-0000-4000-8000-000000000001',kind:'note',name:d.name,body:d.text,size:20,created_at:1700000000000});json={item:notes.at(-1)};status=201}
 else if(p==='/v2/cloud')json={items:[...notes,...(cloudDeleted?[]:[{id:fileId,kind:'file',name:'test.txt',mime:'text/plain',size:5,created_at:1700000000000}]),{id:pdfId,kind:'file',name:'test.pdf',mime:'application/pdf',size:500,created_at:1700000000000}],next_before:null};
 else if(p.endsWith(fileId+'/download'))return route.fulfill({status:200,headers:{'Content-Type':'application/octet-stream','X-Media-Type':'text/plain'},body:'hello'});
 else if(p.endsWith(pdfId+'/download'))return route.fulfill({status:200,headers:{'Content-Type':'application/octet-stream','X-Media-Type':'application/pdf'},body:samplePdf()});
 else if(method==='DELETE'&&p.endsWith(fileId)){await new Promise(r=>setTimeout(r,650));cloudDeleted=true;json={ok:true}}
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
 assert(!(await page.locator('.chat-panel').isVisible()));await page.locator('.conversation').first().click();await page.locator('.bubble').first().waitFor({state:'visible'});assert(await fits(),'chat overflow at '+width);page.once('dialog',d=>d.accept());await page.locator('.bubble footer button').click();assert.equal(await page.locator('.bubble').count(),0);assert.equal(deleted,false);await page.waitForTimeout(750);assert.equal(deleted,true);
 await page.locator('#message').fill('پیش‌نویس اول');
 const back=async()=>{if(width<760){await page.locator('#chat-back').click();assert(await page.locator('.chat-list').isVisible())}else await page.locator('nav [data-view=chat]').click()};
 if(width<760){assert(!(await page.locator('.chat-list').isVisible()));assert(!(await page.locator('.bottom-nav').isVisible()))}
 await back();assert.equal(await page.locator('#messages .bubble').count(),0);await page.locator('.conversation').filter({hasText:'دوست دوم'}).click();await page.locator('#chat .history-notice button').waitFor({state:'visible'});assert(!(await page.locator('#send').isVisible()));await page.locator('#chat .history-notice button').click();await page.locator('#send').waitFor({state:'visible'});assert.equal(await page.locator('#message').inputValue(),'');await page.locator('#message').fill('پیش‌نویس دوم');await back();await page.locator('.conversation').filter({hasText:'دوست آزمایشی'}).click();await page.locator('#send').waitFor({state:'visible'});assert.equal(await page.locator('#message').inputValue(),'پیش‌نویس اول');await back();await page.locator('.conversation').filter({hasText:'دوست دوم'}).click();await page.locator('#send').waitFor({state:'visible'});assert.equal(await page.locator('#message').inputValue(),'پیش‌نویس دوم');await back();
 await page.evaluate(()=>window.dispatchEvent(new Event('offline')));assert.equal(await page.locator('#connection-status').textContent(),'اتصال اینترنت قطع است');await page.evaluate(()=>window.dispatchEvent(new Event('online')));await page.locator('#connection-status').waitFor({state:'hidden'});
 await page.locator('nav [data-view=cloud]').click();await page.locator('#cloud-message').waitFor({state:'visible'});assert(!(await page.locator('#cloud-tools').isVisible()));await page.locator('#cloud-search-toggle').click();await page.locator('#query').fill('test');await page.locator('#cloud-filter button.search-apply').click();await page.locator('.cloud-filter-status').waitFor({state:'visible'});assert((await page.locator('.cloud-filter-status').textContent()).includes('test'));await page.locator('.cloud-filter-status button').click();await page.locator('.cloud-filter-status').waitFor({state:'hidden'});assert.equal(await page.locator('#query').inputValue(),'');await page.locator('#cloud-search-toggle').click();const textCard=page.locator('.saved-bubble').filter({hasText:'test.txt'}),pdfCard=page.locator('.saved-bubble').filter({hasText:'test.pdf'});await textCard.locator('.media-open').click();await page.locator('.media-text').waitFor({state:'visible'});assert.equal(await page.locator('.media-text').textContent(),'hello');await page.locator('.media-close').click();await pdfCard.locator('.media-open').click();await page.locator('.pdf-toolbar span').filter({hasText:'1 / 1'}).waitFor({state:'visible',timeout:15000});await page.locator('.media-close').click();await textCard.locator('summary').click();page.once('dialog',d=>d.accept());await textCard.getByRole('button',{name:'حذف',exact:true}).click();assert.equal(await textCard.count(),0);assert.equal(cloudDeleted,false);await page.waitForTimeout(750);assert.equal(cloudDeleted,true);await page.locator('#cloud-message').fill('یادداشت آزمایشی');await page.locator('#add-note').click();await page.locator('.saved-bubble[data-kind=note]').first().waitFor({state:'visible'});assert.equal(notes.length,1);assert(await fits(),'cloud overflow at '+width);assert.deepEqual(errors,[]);
 console.log('PASS '+width+'px login/register/chat/cloud and Vazirmatn');await page.close();
}
}finally{await browser.close();await new Promise(r=>server.close(r))}
