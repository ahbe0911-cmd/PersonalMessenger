'use strict';
const $=id=>document.getElementById(id);let current=null;
const clear=()=>{current=null;$('key').textContent='';$('result').hidden=true};
$('clear').onclick=clear;
$('make').onsubmit=async e=>{e.preventDefault();if(current&&!confirm('کلید قبلی را ذخیره کرده‌اید؟'))return;clear();$('create').disabled=true;$('status').textContent='در حال ارتباط با سرور…';try{
 const r=await fetch('/keymaker/issue',{method:'POST',credentials:'omit',cache:'no-store',headers:{'Content-Type':'application/json','X-Peyvanik-Keymaker':'1'},body:JSON.stringify({display_name:$('name').value.trim(),username:$('username').value.trim(),accept_key_responsibility:$('accept').checked})});
 const x=await r.json();if(!r.ok)throw Error(x.error||'ISSUE_FAILED');current=x;$('key').textContent=x.access_key;$('result').hidden=false;$('status').textContent='حساب @'+x.account.username+' ایجاد شد.';$('make').reset();
 }catch(e){$('status').textContent=({USERNAME_TAKEN:'نام کاربری تکراری است.',TOO_MANY_REQUESTS:'محدودیت درخواست؛ بعداً دوباره تلاش کنید.',REGISTRATION_LIMIT:'ظرفیت حساب‌ها پر شده.',REGISTRATION_NOT_OPEN:'ثبت‌نام بسته است.'})[e.message]||'ساخت حساب تأیید نشد؛ اگر پاسخ شبکه قطع شد احتمال ایجاد حساب وجود دارد.'}finally{$('create').disabled=false}};
$('copy').onclick=async()=>{if(current)try{await navigator.clipboard.writeText(current.access_key);$('status').textContent='کلید کپی شد.'}catch{$('status').textContent='کپی خودکار ممکن نیست؛ متن کلید را انتخاب کنید.'}};
$('save').onclick=()=>{if(!current)return;const b=new Blob(['Peyvanik\nUsername: '+current.account.username+'\nAccess key: '+current.access_key+'\nKeep private.\n'],{type:'text/plain;charset=utf-8'});const u=URL.createObjectURL(b),a=document.createElement('a');a.href=u;a.download='peyvanik-'+current.account.username+'-key.txt';a.click();setTimeout(()=>URL.revokeObjectURL(u),1000)};
window.addEventListener('pagehide',clear);
