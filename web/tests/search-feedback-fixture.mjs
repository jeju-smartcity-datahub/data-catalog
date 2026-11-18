/** Synthetic SSR/API/browser contract only. No actual login, hub or TLS validation. */
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomUUID, randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { chromium, expect } from '../node_modules/@playwright/test/index.mjs';
import { auditAccessibility } from '../../scripts/accessibility-audit.mjs';

const root=fileURLToPath(new URL('../../',import.meta.url));
const output=path.join(root,'web/test-results/search-feedback');await mkdir(output,{recursive:true});
const origin='http://127.0.0.1:3018';
const org='10000000-0000-0000-0000-000000000001';
const dataset='20000000-0000-0000-0000-000000000001';
const offers=new Map(),nonces=[],postEvents=[];
let api,child,browser,outcome='FAIL',mode='normal',offerMode='normal',formGets=0,posts=0,externalCount=0,unexpectedCount=0,consoleLeaks=0;
const passed=[],pageErrors=[],consoleMessages=[];
const json=(response,value,status=200)=>{response.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'});response.end(JSON.stringify(value));};
try{
  api=createServer((request,response)=>{
    const url=new URL(request.url,'http://127.0.0.1');
    if(request.method!=='GET'){unexpectedCount++;return json(response,{},405);}
    if(url.pathname.endsWith('/facets'))return json(response,{categories:[],organizations:[],regions:[],topics:[],hubs:[],countMode:'EXCLUDE_OWN_GROUP'});
    if(url.pathname==='/api/catalog/v1/search'){
      const nonce=request.headers['x-search-nonce'];if(typeof nonce!=='string'||!/^[-0-9a-f]{36}$/i.test(nonce))throw new Error('Missing fresh search request nonce');nonces.push(nonce);
      const eventId=randomUUID(),feedbackToken=randomBytes(32).toString('base64url');offers.set(eventId,feedbackToken);
      const feedback=offerMode==='none'?null:offerMode==='malformed'?{eventId,feedbackToken:'invalid'}:{eventId,feedbackToken};
      const items=offerMode==='empty'?[]:[{id:dataset,title:'합성 공개 검색 결과',description:'선택형 평가 UI 계약 시험용 메타데이터',organization:{id:org,name:'합성 기관'},category:null,details:{keywords:[],department:null,license:null,updateFrequency:null,spatialCoverage:null,temporalStart:null,temporalEnd:null},regions:[],topics:[],hubs:[],publishedAt:'2026-10-26T23:00:00Z',publicationNo:1,accessState:'METADATA_ONLY'}];
      return json(response,{items,page:{number:0,size:10,totalElements:items.length,totalPages:items.length?1:0},interpretation:{ruleVersion:'SYNTHETIC_UI_FIXTURE',terms:[],message:'합성 검색 결과입니다.'},matches:[],feedback});
    }
    unexpectedCount++;return json(response,{},404);
  });
  await new Promise((resolve,reject)=>{api.once('error',reject);api.listen(0,'127.0.0.1',resolve);});
  child=spawn(process.execPath,['node_modules/next/dist/bin/next','dev','--hostname','127.0.0.1','--port','3018'],{cwd:path.join(root,'web'),env:{...process.env,SERVER_API_URL:`http://127.0.0.1:${api.address().port}`,NEXT_TELEMETRY_DISABLED:'1'},windowsHide:true,stdio:'ignore'});
  let ready=false;
  for(let attempt=0;attempt<120;attempt++){
    if(child.exitCode!==null)throw new Error('Owned Next dev exited before ready');
    try{if((await fetch(origin+'/datasets')).ok){ready=true;break;}}catch{}
    await new Promise(resolve=>setTimeout(resolve,250));
  }
  if(!ready)throw new Error('Owned Next dev startup timeout');
  browser=await chromium.launch({headless:true});
  const context=await browser.newContext({viewport:{width:1440,height:1080}});
  await context.addCookies([{name:'synthetic-session',value:'fixture-only',url:origin,httpOnly:true,sameSite:'Lax'}]);
  await context.route('**/*',async route=>{
    const request=route.request(),url=new URL(request.url());
    if(url.origin!==origin){externalCount++;return route.abort();}
    if(url.pathname==='/api/catalog/v1/search-feedback-form'){
      formGets++;assert.equal(request.method(),'GET');assert.equal(url.search,'');assert.equal(request.headers().referer,undefined);
      if(mode==='form-failure')return route.fulfill({status:503,body:''});
      if(mode==='form-network')return route.abort('failed');
      if(mode==='slow')await new Promise(resolve=>setTimeout(resolve,350));
      const form=mode==='bad-header'?{csrfHeaderName:'Authorization',csrfToken:'fixture-csrf'}:mode==='bad-token'?{csrfHeaderName:'X-CSRF-TOKEN',csrfToken:'bad\ntoken'}:mode==='large-form'?{csrfHeaderName:'X-CSRF-TOKEN',csrfToken:'X'.repeat(9000)}:{csrfHeaderName:'X-CSRF-TOKEN',csrfToken:'fixture-csrf'};
      return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(form)});
    }
    if(url.pathname==='/api/catalog/v1/search-feedback'){
      posts++;assert.equal(request.method(),'POST');assert.equal(url.search,'');
      assert.equal(request.headers()['content-type'],'application/json');assert.equal(request.headers()['x-csrf-token'],'fixture-csrf');assert.equal(request.headers().origin,origin);assert.equal(request.headers().referer,undefined);
      assert.ok(request.headers().cookie?.includes('synthetic-session='));
      const body=request.postDataJSON();
      if(Object.keys(body).sort((left,right)=>left.localeCompare(right)).join(',')!=='eventId,feedbackToken,rating'||offers.get(body.eventId)!==body.feedbackToken||!['HELPFUL','NOT_HELPFUL'].includes(body.rating))throw new Error('Unexpected fixed-choice feedback request contract');
      postEvents.push({eventId:body.eventId,rating:body.rating});
      if(mode==='network')return route.abort('failed');
      const status=mode.startsWith('http-')?Number(mode.slice(5)):204;
      return route.fulfill({status,body:status===204?'':'{}',contentType:'application/json'});
    }
    return route.continue();
  });
  const page=await context.newPage();page.on('pageerror',error=>pageErrors.push(error.name));page.on('console',message=>consoleMessages.push(message.text()));
  const open=async()=>{await page.goto(origin+'/datasets?q=synthetic');await expect(page.getByRole('region',{name:'검색 결과가 도움이 되었나요?',exact:true})).toBeVisible();};
  const widget=page.getByRole('region',{name:'검색 결과가 도움이 되었나요?',exact:true});
  const helpful=()=>widget.getByRole('button',{name:'도움됨',exact:true});const negative=()=>widget.getByRole('button',{name:'도움 안 됨',exact:true});
  await open();assert.equal(formGets,0);assert.equal(posts,0);await expect(widget).toContainText('5년간 보관');
  await expect(widget.locator('input,textarea')).toHaveCount(0);await expect(helpful()).toBeEnabled();
  passed.push('optional two-choice UI makes no form request or POST before interaction and has no free-text input');
  mode='slow';await helpful().evaluate(button=>{button.click();button.click();});await expect(helpful()).toBeDisabled();await expect(negative()).toBeDisabled();
  await expect(widget.getByRole('status')).toContainText('평가를 저장했습니다');assert.equal(formGets,1);assert.equal(posts,1);await expect(helpful()).toHaveAttribute('aria-pressed','true');await expect(negative()).toHaveAttribute('aria-pressed','false');
  passed.push('double click sends one fixed rating with same-origin cookie, CSRF and no Referer/query parameter');
  mode='normal';await open();await negative().click();await expect(widget.getByRole('status')).toContainText('평가를 저장했습니다');assert.equal(posts,2);assert.notEqual(postEvents[0].eventId,postEvents[1].eventId);assert.equal(postEvents[1].rating,'NOT_HELPFUL');
  assert.equal(new Set(nonces).size,nonces.length);
  passed.push('new identical search receives a fresh offer/event and nonce without reusing another result evaluation');
  for(const scenario of ['bad-header','bad-token','large-form','form-failure','form-network']){
    mode=scenario;await open();const before=posts;await helpful().click();await expect(widget.getByRole('alert')).toContainText('평가를 시작하지 못했습니다');await expect(helpful()).toBeEnabled();assert.equal(posts,before);
  }
  mode='normal';await helpful().click();await expect(widget.getByRole('status')).toContainText('평가를 저장했습니다');
  passed.push('invalid/oversized CSRF form or preflight failure refuses POST and permits explicit preflight retry');
  for(const status of [400,403,404,409,410,422]){
    mode=`http-${status}`;await open();const before=posts;await helpful().click();await expect(widget.getByRole('alert')).toBeVisible();await expect(helpful()).toBeDisabled();await expect(negative()).toBeDisabled();assert.equal(posts,before+1);await expect(widget.getByText('평가를 저장했습니다',{exact:false})).toHaveCount(0);
  }
  passed.push('validation/security/expired/already-rated responses are explicit terminal refusals and never show success');
  for(const scenario of ['http-500','http-200','network']){
    mode=scenario;await open();const before=posts;await helpful().click();await expect(widget.getByRole('alert')).toContainText('저장 여부를 확인하지 못했습니다');await expect(helpful()).toBeDisabled();await expect(negative()).toBeDisabled();await new Promise(resolve=>setTimeout(resolve,100));assert.equal(posts,before+1);
  }
  passed.push('unexpected success status, server failure and lost POST response remain uncertain with no automatic retry');
  mode='normal';offerMode='none';const before=formGets;await page.goto(origin+'/datasets');await expect(widget).toHaveCount(0);assert.equal(formGets,before);await expect(page.getByRole('status').filter({hasText:'검색 이력 기록이 일시적으로 불가'})).toBeVisible();
  offerMode='malformed';await page.goto(origin+'/datasets');await expect(widget).toHaveCount(0);assert.equal(formGets,before);await expect(page.getByRole('status').filter({hasText:'이번 검색의 평가를 제공할 수 없습니다'})).toBeVisible();
  passed.push('missing or malformed feedback capability omits evaluation without inventing success');
  offerMode='empty';await open();await expect(page.getByRole('heading',{name:'검색 결과가 없습니다',exact:true})).toBeVisible();await expect(helpful()).toBeEnabled();
  passed.push('zero-result searches can receive optional evaluation');
  await auditAccessibility(page,'search-feedback-desktop');await widget.screenshot({path:path.join(output,'desktop.png')});await page.setViewportSize({width:390,height:844});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=390));await auditAccessibility(page,'search-feedback-mobile');await widget.screenshot({path:path.join(output,'mobile.png')});
  assert.equal(await page.evaluate(()=>Object.keys(localStorage).length),0);assert.equal(await page.evaluate(()=>Object.keys(sessionStorage).length),0);
  const visible=await page.locator('body').innerText();for(const secret of offers.values()){assert.ok(!visible.includes(secret));assert.ok(!page.url().includes(secret));consoleLeaks+=consoleMessages.filter(message=>message.includes(secret)).length;}
  const source=await readFile(path.join(root,'web/src/features/SearchFeedback.tsx'),'utf8');assert.ok(!/console\.|localStorage|sessionStorage/.test(source));assert.equal(consoleLeaks,0);assert.equal(externalCount,0);assert.equal(unexpectedCount,0);assert.deepEqual(pageErrors,[]);
  passed.push('desktop/mobile accessibility, no visible/URL/console token, no persistent browser storage or external requests');
  outcome='PASS';console.log(JSON.stringify({result:outcome,checks:passed.length,output,actualServer:false,actualLogin:false,tlsValidation:false}));
}finally{
  await browser?.close();child?.kill();if(api)await new Promise(resolve=>api.close(resolve));
  await writeFile(path.join(output,'result.json'),JSON.stringify({outcome,kind:'SYNTHETIC_SEARCH_FEEDBACK_UI_CONTRACT',recordedAt:new Date().toISOString(),realServer:false,realLogin:false,tlsValidation:false,passed,formGets,posts,freshSearchNonces:nonces.length,uniqueSearchNonces:new Set(nonces).size,externalRequests:externalCount,unexpectedRequests:unexpectedCount,browserErrorTypes:pageErrors,consoleTokenLeaks:consoleLeaks},null,2)+'\n');
}
