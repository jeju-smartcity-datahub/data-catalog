/** Isolated fixture responses: no real credentials, login, hub, DB or production access. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { chromium } from '../../web/node_modules/playwright/index.mjs';
import { expect } from '../../web/node_modules/@playwright/test/index.mjs';
import { auditAccessibility } from '../../scripts/accessibility-audit.mjs';
const root=fileURLToPath(new URL('../../',import.meta.url)),origin='http://127.0.0.1:3016',output=path.join(root,'admin/test-results/privacy-ui');
await mkdir(output,{recursive:true});
const server=spawn(process.execPath,[path.join(root,'admin/node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port','3016','--strictPort'],{cwd:path.join(root,'admin'),windowsHide:true,stdio:'pipe'});
let startup='',browser,outcome='FAIL';server.stdout.on('data',c=>{startup=(startup+c).slice(-4000);});server.stderr.on('data',c=>{startup=(startup+c).slice(-4000);});
const passed=[],errors=[],unexpected=[],external=[];
const org='10000000-0000-0000-0000-000000000001',did='20000000-0000-0000-0000-000000000001',rid='30000000-0000-0000-0000-000000000001',eid='40000000-0000-0000-0000-000000000001';
const draft={id:did,slug:'synthetic-private-draft',ownerOrganizationId:org,revisionId:rid,revisionNo:1,state:'DRAFT',title:'합성 비공개 초안',description:'',providerOrganizationId:org,primaryCategoryId:null,visibility:'PRIVATE',lockVersion:0,currentPublicationId:null,reviewRequestId:null,reviewDecisionId:null,details:{department:null,license:null,updateFrequency:null,spatialCoverage:null,temporalStart:null,temporalEnd:null,keywords:[]},regionIds:[],hubIds:[],topicIds:[]};
const history={id:eid,requestId:eid,occurredAt:'2026-10-27T00:00:00Z',hasQuery:true,normalizedFilters:{categoryId:[org]},resultCount:12,durationMs:20,searchScope:'PUBLIC'};
let platform=true,problemMode='valid',detailMode='current',draftWrites=0,reviewWrites=0,historyReads=0,detailReads=0;
const missing='등록 필수항목을 입력하세요: 설명, 담당부서, 라이선스, 키워드. 초안은 보관할 수 있습니다.';
try {
  for(let i=0;;i++){if(server.exitCode!==null)throw new Error(startup);try{if((await fetch(origin)).ok)break;}catch{}if(i>=100)throw new Error('Owned Vite startup timeout');await new Promise(r=>setTimeout(r,100));}
  browser=await chromium.launch({headless:true});const context=await browser.newContext({viewport:{width:1440,height:1080}});
  await context.route('**/*',async route=>{
    const request=route.request(),url=new URL(request.url()),method=request.method();
    if(url.origin!==origin){external.push(url.origin);return route.abort();}if(!url.pathname.startsWith('/api/'))return route.continue();
    const respond=(body,status=200,headers={})=>route.fulfill({status,contentType:'application/json',headers,body:JSON.stringify(body)});
    if(url.pathname==='/api/admin/v1/session')return respond({userId:'90000000-0000-0000-0000-000000000001',organizationIds:[org],authorities:[platform?'ROLE_PLATFORM_ADMIN':'ROLE_ORG_EDITOR'],csrfHeaderName:'X-CSRF-TOKEN',csrfToken:'synthetic-admin-csrf'});
    if(url.pathname==='/api/catalog/v1/datasets')return respond({items:[],page:{number:0,size:10,totalElements:0,totalPages:0}});
    if(url.pathname==='/api/admin/v1/metadata-options')return respond({organizations:[{id:org,name:'합성 기관'}],categories:[],topics:[],regions:[],hubs:[]});
    if(url.pathname==='/api/admin/v1/datasets')return respond({items:[draft],page:{number:0,size:10,totalElements:1,totalPages:1}});
    if(url.pathname===`/api/admin/v1/datasets/${did}/draft`){if(method==='PATCH'){const body=request.postDataJSON();assert.equal(body.description,'');assert.equal(body.visibility,'PRIVATE');assert.deepEqual(body.details.keywords,[]);assert.equal(request.headers()['x-csrf-token'],'synthetic-admin-csrf');draftWrites++;draft.title=body.title;draft.lockVersion++;}return respond(draft,200,{ETag:`"dataset:${did}:${draft.lockVersion}"`});}
    if(url.pathname===`/api/admin/v1/datasets/${did}/draft/fields`)return respond({revisionId:rid,lockVersion:draft.lockVersion,fields:[]},200,{ETag:`"dataset:${did}:${draft.lockVersion}"`});
    if(url.pathname===`/api/admin/v1/datasets/${did}/review-requests`&&method==='POST'){
      reviewWrites++;assert.equal(request.headers()['x-csrf-token'],'synthetic-admin-csrf');assert.ok(request.headers()['idempotency-key']);
      if(problemMode==='malformed')return route.fulfill({status:422,contentType:'application/json',body:'<html>DISALLOWED_DETAIL_VALUE</html>'});
      const detail=problemMode==='unknown-label'?'등록 필수항목을 입력하세요: DISALLOWED_DETAIL_VALUE. 초안은 보관할 수 있습니다.':missing;
      return respond({code:problemMode==='wrong-code'?'OTHER':'REQUIRED_METADATA_MISSING',detail,debug:problemMode==='oversize'?'x'.repeat(5000):'DISALLOWED_FIELD_VALUE'},problemMode==='wrong-status'?400:422);
    }
    if(url.pathname==='/api/admin/v1/search-history'){assert.equal(platform,true);historyReads++;return respond([history]);}
    if(url.pathname==='/api/admin/v1/analytics/search-statistics')return respond({searchCount:1,zeroResultCount:0,resultCountSum:12,durationMsSum:20});
    if(url.pathname===`/api/admin/v1/search-history/${eid}`){
      assert.equal(platform,true);detailReads++;if(detailMode==='expired')return respond({code:'SEARCH_EVENT_UNAVAILABLE'},404);
      return respond({eventId:eid,occurredAt:history.occurredAt,expiresAt:'2031-10-27T00:00:00Z',queryRepresentation:detailMode==='legacy'?null:{policyVersion:'catalog-search-privacy-v1',intent:'CONTROLLED_SEARCH',controlledTerms:['JEJU','PARKING','DISALLOWED_DETAIL_VALUE'],hasUnclassifiedTerms:true},returnedResults:detailMode==='legacy'?[]:[{datasetId:did,publicationNo:3,ordinal:0}],rating:detailMode==='legacy'?null:'NOT_HELPFUL',ratedAt:detailMode==='legacy'?null:'2026-10-27T00:01:00Z',returnedPage:1,returnedSize:10,resultScope:'RETURNED_PUBLIC_PAGE',legacyIncomplete:detailMode==='legacy'});
    }
    unexpected.push(`${method} ${url.pathname}`);return respond({code:'UNEXPECTED_FIXTURE'},500);
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  const openDraft=async()=>{await page.goto(`${origin}/metadata?datasetId=${did}`);const modal=page.getByRole('dialog',{name:'메타데이터 상세',exact:true});await expect(modal.getByRole('textbox', { name: /^데이터셋 이름(?:\s|\*|$)/ })).toBeVisible();return modal;};
  let modal=await openDraft();await expect(modal.getByRole('textbox', { name: /^데이터 설명/ })).not.toHaveAttribute('required','');
  const accessibility=await context.newCDPSession(page);
  const requiredInputs=[modal.getByRole('textbox', { name: /^데이터 설명/ }),modal.getByRole('textbox', { name: /^담당부서/ }),modal.getByRole('textbox',{name:/^라이선스/}),modal.getByRole('textbox',{name:/^키워드/})];
  const checkRequiredDescriptions=async expected=>{
    for(const input of requiredInputs)await expect(input).toHaveAccessibleDescription(expected);
    await expect(modal.getByRole('status').filter({hasText:'필수 7개 중'})).toHaveText(expected);
    const document=await accessibility.send('DOM.getDocument');
    const {nodeId}=await accessibility.send('DOM.querySelector',{nodeId:document.root.nodeId,selector:'input[maxlength="200"][aria-describedby]'});
    assert.ok(nodeId,'department input must exist in the browser DOM');
    const tree=await accessibility.send('Accessibility.getPartialAXTree',{nodeId,fetchRelatives:false});
    assert.equal(tree.nodes[0]?.description?.value,expected);
  };
  await checkRequiredDescriptions('필수 7개 중 3개 입력 · 누락: 설명, 담당부서, 라이선스, 키워드');
  await modal.getByRole('textbox', { name: /^담당부서/ }).fill('데이터 담당');
  await checkRequiredDescriptions('필수 7개 중 4개 입력 · 누락: 설명, 라이선스, 키워드');
  await modal.getByRole('textbox', { name: /^담당부서/ }).fill('');
  await checkRequiredDescriptions('필수 7개 중 3개 입력 · 누락: 설명, 담당부서, 라이선스, 키워드');
  await accessibility.detach();
  passed.push('required-input summaries remain live status and descriptions for all four inputs before and after editing');
  await modal.getByRole('textbox', { name: /^데이터셋 이름(?:\s|\*|$)/ }).fill('합성 초안 보관');await modal.getByRole('button',{name:'변경 저장',exact:true}).click();await expect(modal).not.toBeVisible();assert.equal(draftWrites,1);
  passed.push('incomplete PRIVATE draft saves without adding required fields to HTML save gate');
  const submit=async()=>{modal=await openDraft();await modal.getByRole('button',{name:'검토 요청',exact:true}).click();const confirmation=page.getByRole('dialog',{name:'검토 요청',exact:true});await confirmation.getByRole('button',{name:'검토 요청',exact:true}).click();return confirmation;};
  let confirmation=await submit();await expect(confirmation.getByRole('alert')).toContainText(missing);await expect(page.getByText('DISALLOWED_FIELD_VALUE',{exact:false})).toHaveCount(0);await auditAccessibility(page,'required-metadata-error');await page.screenshot({path:path.join(output,'required-metadata-error.png')});
  passed.push('fixed 422 metadata grammar displays only missing allowed labels and draft-retention guidance');
  for(const mode of ['unknown-label','wrong-code','wrong-status','malformed','oversize']){problemMode=mode;confirmation=await submit();await expect(confirmation.getByRole('alert')).toContainText('입력값과 필수 항목');await expect(page.getByText('DISALLOWED_DETAIL_VALUE',{exact:false})).toHaveCount(0);await expect(page.getByText('DISALLOWED_FIELD_VALUE',{exact:false})).toHaveCount(0);}
  passed.push('unknown labels/code/status, invalid JSON and oversized problems fall back without field-value exposure');
  await page.goto(`${origin}/analytics`);await page.getByRole('button',{name:/검색 이력 상세 보기/}).click();const detail=page.getByRole('dialog',{name:'검색 이력 상세',exact:true});await expect(detail.getByRole('heading',{name:'정제된 검색 표현',exact:true})).toBeVisible();await expect(detail).toContainText('제주, 주차, 분류 코드 확인 필요');await expect(detail).toContainText('미분류 입력 있음 · 내용 미보관');await expect(detail).toContainText('도움안됨');await expect(detail).toContainText('프로젝트 보유정책: 5년');await expect(detail.getByRole('link',{name:/검색 당시 게시본/})).toHaveAttribute('href',`/datasets/${did}?version=3`);await expect(detail.getByRole('region',{name:'검색 당시 공개 결과 표'})).toContainText('v3');await expect(detail).toContainText('2페이지');await expect(page.getByText('DISALLOWED_DETAIL_VALUE',{exact:false})).toHaveCount(0);
  await auditAccessibility(page,'sanitized-search-detail');await page.screenshot({path:path.join(output,'search-detail-desktop.png')});
  passed.push('platform admin reads controlled labels, exact returned publication version/page, fixed rating and five-year project retention');
  await page.setViewportSize({width:390,height:844});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=390));await auditAccessibility(page,'sanitized-search-detail-mobile');await page.screenshot({path:path.join(output,'search-detail-mobile.png')});await page.keyboard.press('Escape');
  detailMode='legacy';await page.getByRole('button',{name:/검색 이력 상세 보기/}).click();await expect(detail).toContainText('이전 이력에는 정제된 질의 표현');await expect(detail.getByRole('region',{name:'검색 당시 공개 결과 표'})).toHaveCount(0);await page.keyboard.press('Escape');
  detailMode='expired';await page.getByRole('button',{name:/검색 이력 상세 보기/}).click();await expect(detail.getByRole('alert')).toContainText('보유기간이 만료');detailMode='current';await detail.getByRole('button',{name:'검색 상세 다시 불러오기'}).click();await expect(detail.getByRole('heading',{name:'정제된 검색 표현'})).toBeVisible();await page.keyboard.press('Escape');
  passed.push('legacy and unavailable history stay explicit; retry and 390px dialog accessibility remain usable');
  const before=historyReads;platform=false;await page.reload();await expect(page.getByText('검색 이력 조회에는 플랫폼 관리 권한이 필요합니다.')).toBeVisible();assert.equal(historyReads,before);
  passed.push('organization editor cannot trigger search-history or detail API reads');
  assert.equal(draftWrites,1);assert.equal(reviewWrites,6);assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[]);assert.deepEqual(external,[]);outcome='PASS';console.log(JSON.stringify({result:outcome,checks:passed.length,historyDetailReads:detailReads,output,realServer:false}));
}finally{await browser?.close();server.kill();await writeFile(path.join(output,'admin-browser.json'),JSON.stringify({outcome,kind:'SYNTHETIC_ADMIN_PRIVACY_CONTRACT',recordedAt:new Date().toISOString(),realServer:false,realLogin:false,realHub:false,tlsValidation:false,passed,browserErrors:errors,unexpectedApiRequests:unexpected,externalRequests:external},null,2)+'\n');}
