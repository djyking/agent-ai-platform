import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import fs from 'node:fs';
import {createRequire} from 'node:module';
import {test} from 'node:test';

// Reuse the console's locked DOM test dependency; no second dependency tree.
const require = createRequire(new URL('../../../../platform-console/package.json', import.meta.url));
const {JSDOM} = require('jsdom');
const html = fs.readFileSync(new URL('../../main/resources/web/index.html', import.meta.url), 'utf8');
const script = fs.readFileSync(new URL('../../main/resources/web/app.js', import.meta.url), 'utf8');
const A = '10000000-0000-4000-8000-000000000001';
const B = '20000000-0000-4000-8000-000000000002';
const C = '30000000-0000-4000-8000-000000000003';
const session = {signedIn:true, role:'reviewer', csrfToken:'synthetic-csrf', project:'support-pilot'};
const response = (data, status=200) => ({ok:status>=200&&status<300, status, json:async()=>data});
const approval = (runId, revision='7') => ({body:{id:'approval-'+runId, runId, runRevision:revision, digest:'sha256:'+runId+':'+revision, kind:'HUMAN_INPUT', status:'PENDING', reviewComplete:true, inputRequired:true, summary:'Review '+runId, fields:[], expiresAt:'2026-09-19T00:00:00Z'}, etag:'"r'+revision+'"'});
const flush = async()=>{for(let i=0;i<5;i++)await new Promise(resolve=>setImmediate(resolve));};
function deferred(){let resolve;const promise=new Promise(r=>{resolve=r;});return {promise,resolve};}
async function fixture(t, handler){
  const dom=new JSDOM(html,{url:'http://127.0.0.1:8100/',runScripts:'outside-only'});
  t.after(()=>dom.window.close());
  const requests=[];
  Object.defineProperty(dom.window.crypto,'randomUUID',{value:randomUUID});
  dom.window.fetch=async(path,options)=>{
    const call={path,options,body:options.body===undefined?undefined:JSON.parse(options.body)};
    requests.push(call);
    if(path==='/api/session')return response(session);
    return handler(call);
  };
  dom.window.eval(script);
  await flush();
  const $=id=>dom.window.document.getElementById(id);
  const target=id=>{$('reviewRunId').value=id;$('reviewRunId').dispatchEvent(new dom.window.Event('input',{bubbles:true}));};
  const query=async id=>{target(id);$('reviewForm').dispatchEvent(new dom.window.Event('submit',{bubbles:true,cancelable:true}));await flush();};
  const answer=(text='Answer A',reason='Checked A')=>{$('reviewInput').value=text;$('reviewReason').value=reason;};
  const approve=async()=>{$('approveButton').click();await flush();};
  return {dom,$,requests,target,query,answer,approve};
}

test('changing run immediately clears and disables the previous decision',async t=>{
  const f=await fixture(t,()=>response(approval(A)));
  await f.query(A);f.answer();
  f.target(B);
  assert.equal(f.$('approvalPanel').hidden,true);
  assert.equal(f.$('reviewInput').value,'');assert.equal(f.$('reviewReason').value,'');
  assert.equal(f.$('approveButton').disabled,true);assert.equal(f.$('rejectButton').disabled,true);
  await f.approve();
  assert.equal(f.requests.filter(r=>r.options.method==='POST').length,0);
});

test('failed target query cannot leave the old approval or answer available',async t=>{
  const f=await fixture(t,call=>call.path.includes(A)?response(approval(A)):response({code:'NOT_FOUND'},404));
  await f.query(A);f.answer();await f.query(B);
  assert.equal(f.$('approvalPanel').hidden,true);assert.equal(f.$('reviewInput').value,'');
  assert.equal(f.$('reviewReason').value,'');assert.equal(f.$('approveButton').disabled,true);
  assert.match(f.$('noticeText').textContent,/没有找到/);
});

test('successful target query clears answer and reason and displays exact binding',async t=>{
  const f=await fixture(t,call=>response(approval(call.path.includes(A)?A:C)));
  await f.query(A);f.answer();await f.query(C);
  assert.equal(f.$('reviewInput').value,'');assert.equal(f.$('reviewReason').value,'');
  assert.equal(f.$('approvalRunId').textContent,C);
  assert.equal(f.$('approvalId').textContent,'approval-'+C);
  assert.equal(f.$('approvalRevision').textContent,'7');
  assert.equal(f.$('approvalDigest').textContent,approval(C).body.digest);
  await f.approve();assert.equal(f.requests.filter(r=>r.options.method==='POST').length,0);
  f.answer('Answer C','Checked C');await f.approve();
  const command=f.requests.find(r=>r.options.method==='POST');
  assert.equal(command.path,'/api/runs/'+C+'/decision');
  assert.equal(command.body.input,'Answer C');assert.equal(command.body.reason,'Checked C');
  assert.equal(command.body.digest,approval(C).body.digest);assert.equal(command.body.etag,'"r7"');
  assert.equal(f.$('approvalPanel').hidden,true);
});

test('a late response cannot replace a newer review target',async t=>{
  const a=deferred(),c=deferred();
  const f=await fixture(t,call=>call.path.includes(A)?a.promise:c.promise);
  await f.query(A);await f.query(C);
  c.resolve(response(approval(C)));await flush();f.answer('Answer C','Checked C');
  a.resolve(response(approval(A)));await flush();
  assert.equal(f.$('approvalRunId').textContent,C);
  assert.equal(f.$('reviewInput').value,'Answer C');
});

test('query start clears old review even when target value did not change',async t=>{
  let calls=0;const next=deferred();
  const f=await fixture(t,()=>++calls===1?response(approval(A)):next.promise);
  await f.query(A);f.answer();
  f.$('reviewForm').dispatchEvent(new f.dom.window.Event('submit',{bubbles:true,cancelable:true}));
  assert.equal(f.$('approvalPanel').hidden,true);assert.equal(f.$('approveButton').disabled,true);
  assert.equal(f.$('reviewInput').value,'');assert.equal(f.$('reviewReason').value,'');
  next.resolve(response(approval(A,'8')));await flush();
  assert.equal(f.$('approvalRevision').textContent,'8');
});

test('submission rechecks target even when input event was not dispatched',async t=>{
  const f=await fixture(t,()=>response(approval(A)));
  await f.query(A);f.answer();f.$('reviewRunId').value=B;await f.approve();
  assert.equal(f.requests.filter(r=>r.options.method==='POST').length,0);
  assert.equal(f.$('approvalPanel').hidden,true);
});

test('mismatched approval response is never rendered or enabled',async t=>{
  const f=await fixture(t,()=>response(approval(B)));
  await f.query(A);
  assert.equal(f.$('approvalPanel').hidden,true);assert.equal(f.$('approveButton').disabled,true);
  assert.match(f.$('noticeText').textContent,/不一致/);
});

test('unknown decision retries only the original payload and clears approval on success',async t=>{
  let writes=0;
  const f=await fixture(t,call=>{if(call.options.method==='GET')return response(approval(A));if(++writes===1)throw new TypeError('socket closed');return response({body:{accepted:true}});});
  await f.query(A);f.answer();await f.approve();
  assert.equal(writes,1);assert.equal(f.$('reviewRunId').disabled,true);
  assert.equal(f.$('retryCommand').hidden,false);assert.match(f.$('noticeText').textContent,new RegExp(A));
  f.$('reviewInput').value='Changed after unknown';
  f.$('retryCommand').click();await flush();
  const commands=f.requests.filter(r=>r.options.method==='POST');
  assert.equal(commands.length,2);assert.deepEqual(commands[1].body,commands[0].body);
  assert.equal(f.$('approvalPanel').hidden,true);assert.equal(f.$('reviewRunId').disabled,false);
});

test('expired session clears old approval and pending writes without automatic replay',async t=>{
  let writes=0;
  const f=await fixture(t,call=>{if(call.options.method==='GET')return response(approval(A));writes++;return response({code:'SESSION_REQUIRED'},401);});
  await f.query(A);f.answer();await f.approve();
  assert.equal(writes,1);assert.equal(f.$('workspace').hidden,true);
  assert.equal(f.$('loginPanel').hidden,false);assert.equal(f.$('approvalPanel').hidden,true);
  assert.equal(f.$('reviewInput').value,'');assert.equal(f.$('reviewReason').value,'');
  assert.equal(f.$('reviewRunId').value,'');assert.equal(f.$('retryCommand').hidden,true);
  f.$('accessCode').value='synthetic-new-session-access-code';
  f.$('loginForm').dispatchEvent(new f.dom.window.Event('submit',{bubbles:true,cancelable:true}));
  await flush();assert.equal(writes,1);
  f.$('retryCommand').click();await flush();assert.equal(writes,1);
});

test('every browser request rejects redirects explicitly',async t=>{
  const f=await fixture(t,()=>response(approval(A)));
  await f.query(A);f.answer();await f.approve();
  assert.ok(f.requests.length>=3);
  for(const request of f.requests)assert.equal(request.options.redirect,'error');
});
