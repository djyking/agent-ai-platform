import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createRequire} from 'node:module';
import {test} from 'node:test';

const require = createRequire(new URL('../../../../platform-console/package.json', import.meta.url));
const {JSDOM} = require('jsdom');
const html = fs.readFileSync(new URL('../../main/resources/web/index.html', import.meta.url), 'utf8');
const script = fs.readFileSync(new URL('../../main/resources/web/app.js', import.meta.url), 'utf8');
const flush = async()=>{for(let i=0;i<5;i++)await new Promise(resolve=>setImmediate(resolve));};
const response = data=>({ok:true,status:200,json:async()=>data});

for(const base of ['/','/support/','/tools/support/']){
  test('assets, captcha, login and work requests remain under '+base,async t=>{
    const origin='https://opsagent.example.test';
    const dom=new JSDOM(html,{url:origin+base,runScripts:'outside-only'});
    t.after(()=>dom.window.close());
    const requests=[];
    dom.window.fetch=async(path,options)=>{
      requests.push({path,options});
      assert.ok(path.startsWith(base+'api/'));
      assert.equal(new URL(path,origin).origin,origin);
      assert.equal(options.credentials,'same-origin');
      assert.equal(options.redirect,'error');
      const route=path.slice(base.length-1);
      if(route==='/api/session')return response(options.method==='POST'
        ?{signedIn:true,application:'support',subject:'alice',project:'support',role:'operator',csrfToken:'csrf'}
        :{signedIn:false});
      if(route==='/api/auth/config')return response({mode:'opsagent'});
      if(route==='/api/auth/captcha')return response({captchaId:'a'.repeat(32),imageDataUrl:'data:image/png;base64,aGVsbG8='});
      return response({body:{items:[]}});
    };
    const document=dom.window.document;
    assert.equal(document.querySelector('link[rel=stylesheet]').href,origin+base+'app.css');
    assert.equal(document.getElementById('support-app').src,origin+base+'app.js');
    assert.equal(document.querySelector('.brand').href,origin+base);
    dom.window.eval(script+'\nglobalThis.testSupportApiPath=supportApiPath;');await flush();
    assert.deepEqual(requests.map(call=>call.path),[
      base+'api/session',base+'api/auth/config',base+'api/auth/captcha',
    ]);
    document.getElementById('username').value='alice';
    document.getElementById('password').value='synthetic-password';
    document.getElementById('captchaCode').value='1234';
    document.getElementById('loginForm').dispatchEvent(new dom.window.Event('submit',{bubbles:true,cancelable:true}));
    await flush();
    assert.equal(document.getElementById('password').value,'');
    assert.equal(requests.filter(call=>call.options.method==='POST').length,1);
    assert.ok(requests.some(call=>call.path===base+'api/agents'));
    assert.ok(requests.some(call=>call.path===base+'api/runs'));
    assert.equal(dom.window.testSupportApiPath('/api/runs?cursor=a%2Fb'),base+'api/runs?cursor=a%2Fb');
    for(const invalid of ['https://other.example/api/runs','//other.example/api/runs','/api/../../api/runs','/api/%2e%2e/elsewhere','/api/runs#fragment']){
      assert.throws(()=>dom.window.testSupportApiPath(invalid),/Invalid support API path/);
    }
  });
}

test('an external or ambiguous asset base fails before any request',t=>{
  for(const source of [
    'https://other.example/app.js',
    'https://user:password@opsagent.example.test/support/app.js',
    '/support/app.js?redirect=elsewhere',
    '/support/app.js#fragment',
    '/support%2felsewhere/app.js',
  ]){
    const dom=new JSDOM(html,{url:'https://opsagent.example.test/support/',runScripts:'outside-only'});
    t.after(()=>dom.window.close());
    dom.window.document.getElementById('support-app').src=source;
    let requests=0;
    dom.window.fetch=()=>{requests++;throw new Error('unexpected request');};
    assert.throws(()=>dom.window.eval(script),/Invalid support asset/);
    assert.equal(requests,0);
  }
});
