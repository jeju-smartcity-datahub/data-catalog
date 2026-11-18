/** Synthetic API responses only. No real authentication, hub calls or DB writes. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { chromium } from '../../web/node_modules/playwright/index.mjs';
import { expect } from '../../web/node_modules/@playwright/test/index.mjs';
import { auditAccessibility } from '../../scripts/accessibility-audit.mjs';
const root = fileURLToPath(new URL('../../', import.meta.url));
const output = path.join(root, 'admin/test-results/candidate-mapping-r7');
await mkdir(output, { recursive: true });
const origin = 'http://127.0.0.1:3015';
const server = spawn(process.execPath, [path.join(root, 'admin/node_modules/vite/bin/vite.js'), '--host','127.0.0.1','--port','3015','--strictPort'], { cwd: path.join(root,'admin'), windowsHide: true, stdio: 'pipe' });
let startup = '', browser, outcome = 'FAIL'; server.stdout.on('data', c => { startup = (startup+c).slice(-4000); }); server.stderr.on('data', c => { startup = (startup+c).slice(-4000); });
const passed = [], errors = [], unexpected = [], external = [];
const org = '10000000-0000-0000-0000-000000000001', cid = '20000000-0000-0000-0000-000000000001', id = '30000000-0000-0000-0000-000000000001', did = '40000000-0000-0000-0000-000000000001';
const field = { fieldPath: 'sensor.temperature', displayName: '온도', physicalName: 'sensor.temperature', dataType: 'NUMBER' };
let large = false, conflict = false, applied = false, writes = 0;
const original = { license: 'L'.repeat(500), updateInterval: 'U'.repeat(100), targetRegions: ['R'.repeat(500)], keywords: Array.from({ length: 20 }, (_, i) => `keyword-${i}`), fields: [field] };
const oversized = { license: 'L'.repeat(501), updateInterval: 'U'.repeat(101), targetRegions: ['R'.repeat(501)], keywords: Array.from({ length: 21 }, (_, i) => `keyword-${i}`), fields: [field] };
try {
  for (let i=0; ; i++) { if (server.exitCode !== null) throw new Error(startup); try { if((await fetch(origin)).ok) break; } catch {} if(i>=100)throw new Error('Owned Vite startup timeout'); await new Promise(r=>setTimeout(r,100)); }
  browser = await chromium.launch({ headless: true }); const context = await browser.newContext({ viewport: { width: 1440, height: 1080 } });
  await context.route('**/*', async route => {
    const request=route.request(), url=new URL(request.url()), method=request.method();
    if(url.origin!==origin){external.push(url.origin);return route.abort();}
    if(!url.pathname.startsWith('/api/'))return route.continue();
    const respond=(body,status=200,headers={})=>route.fulfill({status,contentType:'application/json',headers,body:JSON.stringify(body)});
    if(url.pathname==='/api/admin/v1/session')return respond({userId:'90000000-0000-0000-0000-000000000001',organizationIds:[org],authorities:['ROLE_ORG_EDITOR'],csrfHeaderName:'X-CSRF-TOKEN',csrfToken:'candidate-fixture-csrf'});
    if(url.pathname==='/api/catalog/v1/datasets')return respond({items:[],page:{number:0,size:10,totalElements:0,totalPages:0}});
    if(url.pathname==='/api/admin/v1/metadata-options')return respond({organizations:[{id:org,name:'합성 기관'}],categories:[]});
    if(url.pathname==='/api/admin/v1/connections')return respond([{id:cid,organizationId:org,name:'후보 비교 UI 시험',adapterCode:'KETI_DATACORE',endpointConfigured:true,credentialConfigured:false,enabled:true,timeoutSeconds:30,maxAttempts:3,maxPages:100,lockVersion:0,adapterAvailable:true,capabilities:['TEST_CONNECTION','DISCOVER_DATASETS'],updatedAt:'2026-10-26T15:00:00Z'}]);
    if(url.pathname===`/api/admin/v1/connections/${cid}/schedule`)return respond({connectionId:cid,enabled:false,intervalSeconds:3600,lockVersion:0,workerEnabled:true},200,{ETag:`"schedule:${cid}:0"`});
    if(url.pathname===`/api/admin/v1/connections/${cid}/sync-runs`||url.pathname===`/api/admin/v1/connections/${cid}/audit-events`)return respond([]);
    if(url.pathname===`/api/admin/v1/connections/${cid}/publication-policy`)return respond({connectionId:cid,enabled:false,department:null,license:null,keywords:[],defaultCategoryId:null,lockVersion:0},200,{ETag:`"publication-policy:${cid}:0"`});
    if(url.pathname===`/api/admin/v1/connections/${cid}/publication-results`)return respond({items:[]});
    const candidate={id,connectionId:cid,externalId:'synthetic-source',title:'합성 후보',revisionNo:1,state:applied?'APPLIED':'UPDATED',datasetId:did,updatedAt:'2026-10-26T15:00:00Z'};
    if(url.pathname===`/api/admin/v1/connections/${cid}/candidates`)return respond({items:[candidate],page:0,size:20,totalElements:1});
    if(url.pathname===`/api/admin/v1/candidates/${id}`){
      const metadata=large?oversized:original;
      const values={title:'원천 제목',description:'원천 설명',...(!large?{'details.license':metadata.license,'details.updateFrequency':metadata.updateInterval,'details.spatialCoverage':metadata.targetRegions.join(', '),'details.keywords':metadata.keywords}:{}),fields:metadata.fields,hubMetadata:metadata};
      const changes=Object.entries(values).map(([key,value])=>({field:key,previousSourceValue:null,currentValue:large&&key==='title'?'수동 제목':null,sourceValue:typeof value==='string'?value:JSON.stringify(value),sourceChanged:true,manuallyChanged:conflict,conflict,action:conflict?'CONFLICT':'APPLY_SOURCE'}));
      return respond({candidate,description:'원천 설명',sourceHash:'a'.repeat(64),etag:`"candidate:${id}:1:0"`,targetEtag:`"dataset:${did}:0"`,targetState:'DRAFT',changes,conflict,metadata});
    }
    if(url.pathname===`/api/admin/v1/candidates/${id}/applications`&&method==='POST'){
      assert.equal(conflict,false);assert.equal(request.headers()['if-match'],`"candidate:${id}:1:0"`);assert.equal(request.headers()['x-csrf-token'],'candidate-fixture-csrf');assert.ok(request.headers()['idempotency-key']);
      assert.equal(request.postDataJSON().targetEtag,`"dataset:${did}:0"`);writes++;applied=true;
      return respond({id:'50000000-0000-0000-0000-000000000001',candidateId:id,sourceHash:'a'.repeat(64),datasetId:did,revisionId:'60000000-0000-0000-0000-000000000001',lockVersion:1,appliedAt:'2026-10-26T15:00:00Z'});
    }
    unexpected.push(`${method} ${url.pathname}`);return respond({code:'UNEXPECTED_FIXTURE'},500);
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));await page.goto(`${origin}/hubs`);await page.getByRole('button',{name:'변경 비교',exact:true}).click();
  const dialog=page.getByRole('dialog',{name:'수집 후보 변경 비교',exact:true});
  const table=dialog.getByRole('region',{name:'허브 변경 비교 표',exact:true});
  for(const label of ['제목','설명','라이선스','갱신 주기','공간 범위','키워드','필드 정의','허브 메타데이터'])await expect(table.getByRole('rowheader',{name:label,exact:true})).toHaveCount(1);
  await expect(dialog.getByRole('region',{name:'자동 적용 제외 안내',exact:true})).toHaveCount(0);
  const fields=table.getByRole('rowheader',{name:'필드 정의',exact:true}).locator('..');
  await fields.getByText('1개 항목 · 전체 값 보기',{exact:true}).click();await expect(fields).toContainText('sensor.temperature');
  passed.push('eight exact field labels, matching-boundary values, full array content behind collapsed summary');
  large=true;await dialog.getByRole('button',{name:'비교 다시 확인',exact:true}).click();
  const warning=dialog.getByRole('region',{name:'자동 적용 제외 안내',exact:true});await expect(warning.getByRole('heading',{name:'일반 필드 자동 적용 제외 (4건)'})).toBeVisible();
  for(const text of ['500자를 넘습니다 (501자)','100자를 넘습니다 (101자)','20개를 넘습니다 (21개)','원문을 확인하고 메타데이터에서 수정'])await expect(warning).toContainText(text);
  const raw=warning.getByText('라이선스 원문 전체 보기',{exact:true});await raw.click();await warning.getByText('501자 · 전체 값 보기',{exact:true}).first().click();await expect(warning).toContainText(oversized.license);
  passed.push('oversized values show four exclusion reasons and complete preserved source with manual-completion instruction');
  conflict=true;await dialog.getByRole('button',{name:'비교 다시 확인',exact:true}).click();await expect(dialog.getByRole('button',{name:'허브 변경을 초안에 적용',exact:true})).toBeDisabled();assert.equal(writes,0);
  conflict=false;await dialog.getByRole('button',{name:'비교 다시 확인',exact:true}).click();await expect(dialog.getByRole('button',{name:'허브 변경을 초안에 적용',exact:true})).toBeEnabled();
  passed.push('source/manual conflict guard survives warning and refresh changes');
  await auditAccessibility(page,'candidate-comparison-r7-desktop');await page.screenshot({path:path.join(output,'comparison-desktop.png')});await page.setViewportSize({width:390,height:844});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=390));await auditAccessibility(page,'candidate-comparison-r7-mobile');await page.screenshot({path:path.join(output,'comparison-mobile.png')});
  passed.push('comparison with preserved raw values passes desktop/mobile accessibility and document-overflow checks');
  await dialog.getByRole('button',{name:'허브 변경을 초안에 적용',exact:true}).click();await expect(dialog).not.toBeVisible();assert.equal(writes,1);
  await expect(page.getByRole('status').filter({hasText:'별도 확인'})).toContainText('라이선스, 갱신 주기, 공간 범위, 키워드');
  passed.push('successful explicit application retains excluded-field warning and target link in result state');
  assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[]);assert.deepEqual(external,[]);outcome='PASS';console.log(JSON.stringify({result:outcome,checks:passed.length,output,actualServer:false}));
} finally {await browser?.close();server.kill();await writeFile(path.join(output,'browser.json'),JSON.stringify({outcome,kind:'SYNTHETIC_CANDIDATE_UI_CONTRACT',recordedAt:new Date().toISOString(),realHub:false,realServer:false,realLogin:false,tlsValidation:false,passed,browserErrors:errors,unexpectedApiRequests:unexpected,externalRequests:external},null,2)+'\n');}
