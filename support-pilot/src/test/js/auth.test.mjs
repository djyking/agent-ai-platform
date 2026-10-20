import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import fs from 'node:fs';
import {createRequire} from 'node:module';
import {test} from 'node:test';

const require = createRequire(new URL('../../../../platform-console/package.json', import.meta.url));
const {JSDOM} = require('jsdom');
const html = fs.readFileSync(new URL('../../main/resources/web/index.html', import.meta.url), 'utf8');
const script = fs.readFileSync(new URL('../../main/resources/web/app.js', import.meta.url), 'utf8');
const response = (data, status=200) => ({ok:status>=200&&status<300, status, json:async()=>data});
const session = subject => ({signedIn:true, application:'support-client', subject, role:'operator', csrfToken:'csrf-'+subject, project:'support-project'});
const captcha = {captchaId:'a'.repeat(32), imageDataUrl:'data:image/png;base64,aGVsbG8='};
const flush = async()=>{for(let i=0;i<5;i++)await new Promise(resolve=>setImmediate(resolve));};
function deferred(){let resolve;const promise=new Promise(r=>{resolve=r;});return {promise,resolve};}
async function fixture(t, handler, prepare=()=>{}){
  const dom=new JSDOM(html,{url:'http://127.0.0.1:8100/',runScripts:'outside-only'});
  t.after(()=>dom.window.close());
  Object.defineProperty(dom.window.crypto,'randomUUID',{value:randomUUID});
  const requests=[];
  dom.window.fetch=async(path,options)=>{
    const call={path,options,body:options.body===undefined?undefined:JSON.parse(options.body)};
    requests.push(call);
    return handler(call);
  };
  prepare(dom.window);
  dom.window.eval(script);
  await flush();
  return {dom,requests,$:id=>dom.window.document.getElementById(id)};
}
const empty = ()=>response({body:{items:[]}});

test('formal login loads captcha, submits credentials once, and clears password immediately',async t=>{
  const login=deferred();
  const f=await fixture(t,call=>{
    if(call.path==='/api/session')return call.options.method==='POST'?login.promise:response({signedIn:false});
    if(call.path==='/api/auth/config')return response({mode:'opsagent',captchaRequired:true});
    if(call.path==='/api/auth/captcha')return response(captcha);
    return empty();
  });
  assert.equal(f.$('identityFields').hidden,false);
  assert.equal(f.$('accessFields').hidden,true);
  assert.equal(f.$('accessCode').disabled,true);
  assert.equal(f.$('captchaImage').src,captcha.imageDataUrl);
  f.$('username').value='alice';f.$('password').value='synthetic-password';
  f.$('captchaCode').value='1234';f.$('loginRole').value='operator';
  f.$('loginForm').dispatchEvent(new f.dom.window.Event('submit',{bubbles:true,cancelable:true}));
  assert.equal(f.$('password').value,'');
  f.$('loginForm').dispatchEvent(new f.dom.window.Event('submit',{bubbles:true,cancelable:true}));
  assert.equal(f.requests.filter(r=>r.options.method==='POST').length,1);
  assert.deepEqual(f.requests.find(r=>r.options.method==='POST').body,{
    username:'alice',password:'synthetic-password',captchaId:captcha.captchaId,captchaCode:'1234',role:'operator',
  });
  login.resolve(response(session('alice')));await flush();
  assert.equal(f.$('loginPanel').hidden,true);
  assert.match(f.$('roleLabel').textContent,/alice/);
  assert.equal(f.dom.window.sessionStorage.length,0);
});

test('access-code controls are only exposed by explicit local-test configuration',async t=>{
  const f=await fixture(t,call=>{
    if(call.path==='/api/session')return response({signedIn:false});
    if(call.path==='/api/auth/config')return response({mode:'local-test'});
    throw new Error('unexpected request');
  });
  assert.equal(f.$('identityFields').hidden,true);
  assert.equal(f.$('username').disabled,true);
  assert.equal(f.$('accessFields').hidden,false);
  assert.equal(f.$('accessCode').disabled,false);
  assert.equal(f.requests.some(r=>r.path==='/api/auth/captcha'),false);
});

test('late responses from the prior user never render in a new user session',async t=>{
  const old=deferred();let runs=0;
  const f=await fixture(t,call=>{
    if(call.path==='/api/session')return response(session(call.options.method==='POST'?'bob':'alice'));
    if(call.path==='/api/runs'&&++runs===1)return old.promise;
    return empty();
  });
  f.$('username').value='bob';f.$('password').value='synthetic-password';
  f.$('loginForm').dispatchEvent(new f.dom.window.Event('submit',{bubbles:true,cancelable:true}));await flush();
  old.resolve(response({body:{items:[{id:'alice-secret-run',releaseRef:{agentId:'alice-only-title'},status:'COMPLETED',createdAt:'2026-01-01T00:00:00Z'}]}}));
  await flush();
  assert.doesNotMatch(f.$('runList').textContent,/alice-only-title/);
  assert.match(f.$('roleLabel').textContent,/bob/);
});

test('unconfirmed writes survive refresh only in their exact user scope and never auto-replay',async t=>{
  const stored={path:'/api/runs',body:{agentId:'faq',releaseId:'synthetic-release',releaseRef:{agentId:'faq',releaseId:'synthetic-release',digest:'sha256:'+'a'.repeat(64)},inputs:{question:'preserved question'},requestKey:'original-command-key'},reviewRunId:null};
  const key='support-pending-v1:'+JSON.stringify(['support-client','support-project','alice','operator']);
  const f=await fixture(t,call=>{
    if(call.path==='/api/session')return response(session(call.options.method==='POST'?'bob':'alice'));
    if(call.options.method==='POST')return response({body:{accepted:true}});
    return empty();
  },window=>window.sessionStorage.setItem(key,JSON.stringify(stored)));
  assert.equal(f.requests.filter(r=>r.options.method==='POST').length,0);
  assert.equal(f.$('retryCommand').hidden,false);
  assert.equal(f.$('createButton').disabled,true);
  f.$('retryCommand').click();await flush();
  const write=f.requests.find(r=>r.options.method==='POST');
  assert.equal(write.path,stored.path);
  assert.deepEqual(write.body,stored.body);
  assert.equal(f.dom.window.sessionStorage.getItem(key),null);
  f.dom.window.sessionStorage.setItem(key,JSON.stringify(stored));
  f.$('username').value='bob';f.$('password').value='synthetic-password';
  f.$('loginForm').dispatchEvent(new f.dom.window.Event('submit',{bubbles:true,cancelable:true}));await flush();
  assert.equal(f.$('retryCommand').hidden,true);
  assert.equal(f.requests.filter(r=>r.options.method==='POST'&&r.path==='/api/runs').length,1);
});

test('new consultations retain the exact published reference before sending an uncertain write',async t=>{
  const releaseRef={agentId:'faq',releaseId:'d1d168fd-ab3a-496b-ae42-cb6fd8e17870',digest:'sha256:'+'b'.repeat(64)};
  const f=await fixture(t,call=>{
    if(call.path==='/api/session')return response(session('alice'));
    if(call.path==='/api/agents')return response({body:{items:[{agentId:'faq',name:'Synthetic policy',version:1,isDefault:true,releaseRef,inputSchema:{properties:{question:{type:'string'}},required:['question']}}]}});
    if(call.path==='/api/runs'&&call.options.method==='POST')return response({code:'TEMPORARILY_UNAVAILABLE',outcomeUnknown:true},503);
    return empty();
  });
  f.$('field-question').value='Synthetic policy question';
  f.$('createForm').dispatchEvent(new f.dom.window.Event('submit',{bubbles:true,cancelable:true}));
  await flush();
  const sent=f.requests.find(call=>call.path==='/api/runs'&&call.options.method==='POST');
  assert.deepEqual(sent.body.releaseRef,releaseRef);
  const key='support-pending-v1:'+JSON.stringify(['support-client','support-project','alice','operator']);
  const pending=JSON.parse(f.dom.window.sessionStorage.getItem(key));
  assert.deepEqual(pending.body,sent.body);
  assert.equal(f.$('retryCommand').hidden,false);
  assert.equal(f.requests.filter(call=>call.path==='/api/runs'&&call.options.method==='POST').length,1);
});

test('an expired session on page load fetches a fresh captcha without a failed login attempt',async t=>{
  const f=await fixture(t,call=>{
    if(call.path==='/api/session')return response({code:'SESSION_REQUIRED'},401);
    if(call.path==='/api/auth/config')return response({mode:'opsagent',captchaRequired:true});
    if(call.path==='/api/auth/captcha')return response(captcha);
    throw new Error('unexpected request');
  });
  assert.equal(f.$('loginPanel').hidden,false);
  assert.equal(f.$('workspace').hidden,true);
  assert.equal(f.$('captchaImage').src,captcha.imageDataUrl);
  assert.equal(f.requests.filter(r=>r.path==='/api/auth/captcha').length,1);
  assert.equal(f.requests.some(r=>r.options.method==='POST'),false);
});

test('session revocation during work clears the consumed captcha and obtains a new one',async t=>{
  let runs=0;
  const f=await fixture(t,call=>{
    if(call.path==='/api/session')return response(session('alice'));
    if(call.path==='/api/runs')return ++runs===1?empty():response({code:'SESSION_REQUIRED'},401);
    if(call.path==='/api/auth/config')return response({mode:'opsagent',captchaRequired:true});
    if(call.path==='/api/auth/captcha')return response(captcha);
    return empty();
  });
  f.$('captchaCode').value='already-consumed';
  f.$('captchaImage').src='data:image/png;base64,b2xk';
  f.$('refreshRuns').click();await flush();
  assert.equal(f.$('loginPanel').hidden,false);
  assert.equal(f.$('captchaCode').value,'');
  assert.equal(f.$('captchaImage').src,captcha.imageDataUrl);
  assert.equal(f.requests.filter(r=>r.path==='/api/auth/captcha').length,1);
});

test('captcha provider rejection does not start an authentication refresh loop',async t=>{
  const f=await fixture(t,call=>{
    if(call.path==='/api/session')return response({code:'SESSION_REQUIRED'},401);
    if(call.path==='/api/auth/config')return response({mode:'opsagent',captchaRequired:true});
    if(call.path==='/api/auth/captcha')return response({code:'UNAUTHENTICATED'},401);
    throw new Error('unexpected request');
  });
  assert.equal(f.$('loginPanel').hidden,false);
  assert.equal(f.requests.filter(r=>r.path==='/api/auth/config').length,1);
  assert.equal(f.requests.filter(r=>r.path==='/api/auth/captcha').length,1);
  assert.equal(f.$('captchaImage').getAttribute('src'),null);
});

for(const latePart of ['run','events']){
  test('a late '+latePart+' response never replaces the newly selected Run',async t=>{
    const a='10000000-0000-4000-8000-000000000001',b='20000000-0000-4000-8000-000000000002';
    const late=deferred();
    const run=id=>({body:{id,status:'COMPLETED',output:{visibility:'AVAILABLE',value:'answer-'+id}},etag:'etag-'+id});
    const events=id=>({body:{items:[{type:'MODEL_COMPLETED',at:id===a?'2025-01-01T00:00:00Z':'2026-01-01T00:00:00Z'}]}});
    const f=await fixture(t,call=>{
      if(call.path==='/api/session')return response(session('alice'));
      if(call.path==='/api/runs')return response({body:{items:[a,b].map(id=>({id,releaseRef:{agentId:id},status:'COMPLETED',createdAt:'2026-01-01T00:00:00Z'}))}});
      if(call.path==='/api/runs/'+a)return latePart==='run'?late.promise:response(run(a));
      if(call.path==='/api/runs/'+a+'/events')return latePart==='events'?late.promise:response(events(a));
      if(call.path==='/api/runs/'+b)return response(run(b));
      if(call.path==='/api/runs/'+b+'/events')return response(events(b));
      return empty();
    });
    const rows=f.$('runList').querySelectorAll('.run-row');
    rows[0].click();await flush();rows[1].click();await flush();
    late.resolve(response(latePart==='run'?run(a):events(a)));await flush();
    assert.equal(f.$('runId').textContent,b);
    assert.equal(f.$('runOutput').textContent,'answer-'+b);
    assert.match(f.$('events').textContent,/2026/);
    assert.doesNotMatch(f.$('events').textContent,/2025/);
  });
}
