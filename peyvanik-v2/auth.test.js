
import test from 'node:test';
import assert from 'node:assert/strict';
import {base32,decode32,totp,matchStep,verifyGoogle,auth} from './auth.js';
const secret='GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ';
test('RFC 6238 SHA1 vectors',async()=>{for(const [time,code] of [[59,'94287082'],[1111111109,'07081804'],[1111111111,'14050471'],[1234567890,'89005924'],[2000000000,'69279037'],[20000000000,'65353130']])assert.equal(await totp(secret,Math.floor(time/30),8),code)});
test('manual key round trip',()=>{const x=Uint8Array.from({length:20},(_,i)=>i);assert.deepEqual(decode32(base32(x)),x)});
test('TOTP time window and replay',async()=>{const t=1234567890000,s=Math.floor(t/30000),code=await totp(secret,s);assert.equal(await matchStep(secret,code,t),s);assert.equal(await matchStep(secret,code,t,s),null);assert.equal(await matchStep(secret,'abc',t),null);assert.equal(await matchStep(secret,code,t+90000),null)});
test('registration fails closed without configuration',async()=>{const r=await auth(new Request('https://example.test/v2/auth/nonce',{method:'POST'}),{});assert.equal(r.status,503)});
test('Google JWT validates signature and claims, rejects wrong audience/nonce and expiry',async()=>{
 const keys=await crypto.subtle.generateKey({name:'RSASSA-PKCS1-v1_5',modulusLength:2048,publicExponent:new Uint8Array([1,0,1]),hash:'SHA-256'},true,['sign','verify']);const jwk=await crypto.subtle.exportKey('jwk',keys.publicKey);Object.assign(jwk,{kid:'test',alg:'RS256',use:'sig'});
 const b64=x=>Buffer.from(JSON.stringify(x)).toString('base64url'),now=Date.now(),s=Math.floor(now/1000),aud='test.apps.googleusercontent.com',nonce='nonce';
 const base={iss:'https://accounts.google.com',aud,exp:s+300,iat:s,nonce,sub:'12345',email:'test@gmail.com',email_verified:true};
 async function sign(c){const h=b64({alg:'RS256',kid:'test'})+'.'+b64(c);const sig=await crypto.subtle.sign('RSASSA-PKCS1-v1_5',keys.privateKey,new TextEncoder().encode(h));return h+'.'+Buffer.from(sig).toString('base64url')}
 const resolve=async()=>[jwk],valid=await sign(base);assert.equal((await verifyGoogle(valid,aud,nonce,resolve,now)).sub,'12345');
 for(const overrides of [{aud:'wrong'},{nonce:'wrong'},{exp:s-1},{email_verified:false},{email:'test@example.com'}])await assert.rejects(verifyGoogle(await sign({...base,...overrides}),aud,nonce,resolve,now));
 const parts=valid.split('.');parts[1]=b64({...base,sub:'attacker'});await assert.rejects(verifyGoogle(parts.join('.'),aud,nonce,resolve,now));
});
