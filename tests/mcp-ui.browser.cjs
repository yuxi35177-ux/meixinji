const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs/promises'),http=require('node:http'),path=require('node:path');
const root=path.resolve(__dirname,'..');
(async()=>{
 let echoCalls=0,legacyStream;const requests=[];
 const result=(m)=>{
  if(m.method==='initialize')return {protocolVersion:m.params.protocolVersion,capabilities:{tools:{}},serverInfo:{name:'test-only',version:'1'}};
  if(m.method==='tools/list')return m.params?.cursor?{tools:[{name:'extra',description:'extra',inputSchema:{type:'object',properties:{}}}]}:{tools:[{name:'echo',description:'Echo text',inputSchema:{type:'object',properties:{text:{type:'string'}},required:['text']}}],nextCursor:'next'};
  if(m.method==='tools/call'){echoCalls++;return {content:[{type:'text',text:'工具结果：'+m.params.arguments.text}]};}
  return {};
 };
 const server=http.createServer(async(req,res)=>{try{
  const url=new URL(req.url,'http://localhost');
  if(url.pathname==='/sse'){
   res.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-cache'});legacyStream=res;res.write('event: endpoint\ndata: /messages?session=test\n\n');req.on('close',()=>{if(legacyStream===res)legacyStream=null});return;
  }
  if(['/mcp','/stream','/messages','/v1/chat/completions'].includes(url.pathname)){
   let body='';for await(const chunk of req)body+=chunk;const m=JSON.parse(body);requests.push({path:url.pathname,method:m.method,headers:req.headers});
   if(url.pathname==='/v1/chat/completions'){
    const prior=m.messages.find(v=>v.role==='tool');let response;
    if(prior)response={choices:[{message:{role:'assistant',content:JSON.stringify({lines:[prior.content.includes('用户拒绝')?'已取消工具':'已取得工具结果']})},finish_reason:'stop'}]};
    else if(m.tools?.length)response={choices:[{message:{role:'assistant',content:null,tool_calls:[{id:'test-call-1',type:'function',function:{name:m.tools.find(t=>t.function.name.endsWith('echo')).function.name,arguments:'{"text":"from AI"}'}}]},finish_reason:'tool_calls'}]};
    else response={choices:[{message:{role:'assistant',content:'普通回复'},finish_reason:'stop'}]};
    res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(response));return;
   }
   if(url.pathname==='/messages'){
    res.writeHead(202);res.end();if(m.id!==undefined)legacyStream?.write('event: message\ndata: '+JSON.stringify({jsonrpc:'2.0',id:m.id,result:result(m)})+'\n\n');return;
   }
   if(req.headers['x-test']!=='works'){res.writeHead(401);res.end();return;}
   if(m.method!=='initialize'&&(!req.headers['mcp-protocol-version']||req.headers['mcp-session-id']!=='session-test')){res.writeHead(400);res.end();return;}
   if(m.id===undefined){res.writeHead(202);res.end();return;}
   const answer=JSON.stringify({jsonrpc:'2.0',id:m.id,result:result(m)});
   if(url.pathname==='/stream'){
    res.writeHead(200,{'Content-Type':'text/event-stream','Mcp-Session-Id':'session-test'});const bytes=Buffer.from('event: message\ndata: '+answer+'\n\n');res.write(bytes.subarray(0,bytes.length-8));setTimeout(()=>res.end(bytes.subarray(bytes.length-8)),5);return;
   }
   res.writeHead(200,{'Content-Type':'application/json','Mcp-Session-Id':'session-test'});res.end(answer);return;
  }
  const filename=path.join(root,decodeURIComponent(url.pathname==='/'?'/index.html':url.pathname));if(!filename.startsWith(root+'/'))throw Error('path');const body=await fs.readFile(filename);res.setHeader('Content-Type',({'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.woff2':'font/woff2','.png':'image/png'})[path.extname(filename)]||'application/octet-stream');res.end(body);
 }catch(e){res.writeHead(500);res.end(e.message)}});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const base='http://127.0.0.1:'+server.address().port;
 const browser=await chromium.launch({executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE,headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});
 try{
  const context=await browser.newContext({viewport:{width:393,height:852},isMobile:true,hasTouch:true});await context.route('**/*',r=>new URL(r.request().url()).origin===base?r.continue():r.abort());
  const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto(base);await page.locator('#boot-splash-enter').click();await page.waitForFunction(()=>window.RP_AI&&window.XXJ_MCP&&typeof applyGlobalUiTypography==='function');
  await page.evaluate(()=>{for(const s of XXJ_MCP.config.servers)s.enabled=false;openMyScreen()});await page.locator('#my-open-mcp').click();assert(await page.locator('#mcp-screen').isVisible());
  await page.locator('#mcp-name').fill('本机测试');await page.locator('#mcp-url').fill(base+'/mcp');await page.locator('#mcp-headers').fill('{"X-Test":"works"}');await page.getByRole('button',{name:'保存服务',exact:true}).click();await page.locator('#mcp-connect').click();await page.waitForFunction(()=>document.getElementById('mcp-status').textContent.includes('连接成功'));assert.match(await page.locator('#mcp-status').textContent(),/2 个工具/);
  await page.locator('#mcp-tool-select').selectOption('echo');await page.locator('#mcp-tool-args').fill('{"text":"手动测试"}');await page.locator('#mcp-call').click();await page.waitForFunction(()=>document.getElementById('mcp-result').textContent.includes('工具结果：手动测试'));assert.equal(echoCalls,1);
  await page.locator('#mcp-enabled').check();await page.evaluate(base=>RP_AI.saveConfig({baseUrl:base+'/v1',apiKey:'local-test-only',model:'test-model'}),base);
  await page.evaluate(()=>{window.completion=null;window.completionError=null;RP_AI.chatCompletions({messages:[{role:'system',content:'[MCP 工具] test'},{role:'user',content:'测试工具'}]}).then(v=>window.completion=v,e=>window.completionError=e.message)});
  await page.getByRole('dialog',{name:'确认 MCP 工具调用'}).waitFor();await page.getByRole('button',{name:'允许执行',exact:true}).click();await page.waitForFunction(()=>window.completion||window.completionError);assert.equal(await page.evaluate(()=>completionError),null);assert.match(await page.evaluate(()=>completion.choices[0].message.content),/已取得工具结果/);assert.equal(echoCalls,2);
  await page.evaluate(()=>{window.completion=null;RP_AI.chatCompletions({messages:[{role:'system',content:'[MCP 工具] test'},{role:'user',content:'测试拒绝'}]}).then(v=>window.completion=v)});await page.getByRole('button',{name:'拒绝',exact:true}).click();await page.waitForFunction(()=>window.completion);assert.match(await page.evaluate(()=>completion.choices[0].message.content),/已取消工具/);assert.equal(echoCalls,2);
  const streams=await page.evaluate(async base=>{
   XXJ_MCP.importConfig([{name:'流式 HTTP',url:base+'/stream',headers:{'X-Test':'works'},transport:'http',enabled:false},{name:'旧版 SSE',url:base+'/sse',transport:'sse',enabled:false}]);
   const a=XXJ_MCP.config.servers.at(-2),b=XXJ_MCP.config.servers.at(-1);await XXJ_MCP.connect(a.id);const streamed=await XXJ_MCP.invoke(a.id,'echo',{text:'UTF-8 流'});await XXJ_MCP.connect(b.id);const legacy=await XXJ_MCP.invoke(b.id,'echo',{text:'旧版流'});return {streamed,legacy};
  },base);assert.match(streams.streamed.content[0].text,/UTF-8 流/);assert.match(streams.legacy.content[0].text,/旧版流/);
  console.log('MCP: HTTP + session + pagination + custom headers + SSE response + legacy SSE + manual tool + chat approval/denial passed');
  await page.locator('#mcp-close').click();await page.evaluate(()=>openSettings());
  const names=['#my-open-settings .my-row-l','#reality-test-list strong','.settings-masthead-title','.chat-scene-pull','#mcp-screen h3'];
  async function size(n){await page.evaluate(n=>{const r=document.getElementById('settings-font-size');r.value=n;r.dispatchEvent(new Event('input',{bubbles:true}));},n);await page.waitForTimeout(120);return page.evaluate(names=>names.map(s=>parseFloat(getComputedStyle(document.querySelector(s)).fontSize)),names);}
  const large=await size('20'),small=await size('12');for(let i=0;i<names.length;i++){assert(Math.abs(large[i]/small[i]-20/12)<.1,names[i]+' follows one global font scale')}
  await page.evaluate(()=>{const s=document.createElement('style');s.textContent='#px-font-fixture{font-size:20px}';document.head.appendChild(s);const p=document.createElement('p');p.id='px-font-fixture';p.textContent='px fixed font';document.body.appendChild(p)});await page.waitForTimeout(100);assert.equal(await page.locator('#px-font-fixture').evaluate(el=>parseFloat(getComputedStyle(el).fontSize)),15);
  await page.evaluate(()=>{const card=document.querySelector('#chat-screen .chat-identity-wrap');card.parentElement.id='qa-chat-fixture';document.body.appendChild(card);card.id='qa-identity';document.body.appendChild(document.getElementById('chat-identity-toggle'));});const toggle=page.locator('#chat-identity-toggle');await toggle.click();assert.equal(await toggle.getAttribute('aria-expanded'),'false');assert.equal(await page.locator('#qa-identity>.chat-slab').isVisible(),false);await toggle.click();assert.equal(await page.locator('#qa-identity>.chat-slab').isVisible(),true);
  await page.evaluate(()=>{window.permissionCalls=[];window.AndroidShell={getScreenshotCapabilities:()=>JSON.stringify({ready:false}),canDrawOverlay:()=>false,openAccessibilitySettings:()=>{permissionCalls.push('accessibility');return true},requestOverlayPermission:()=>{permissionCalls.push('overlay');return true},openPermissionAppInfo:()=>{permissionCalls.push('app');return true}};XXJ_REALITY.config.enabled=true;XXJ_REALITY.config.screenshot=true;XXJ_REALITY.config.overlay=true;XXJ_REALITY.open();});
  await page.getByRole('button',{name:'测试截屏',exact:true}).click();await page.getByRole('button',{name:'测试悬浮窗',exact:true}).click();assert.deepEqual(await page.evaluate(()=>permissionCalls),['accessibility','overlay']);
  assert.deepEqual(errors,[]);console.log('UI: fonts across apps and px rules, profile folding, missing-permission settings actions passed');
  await page.screenshot({path:path.join(process.env.QA_OUTPUT_DIR||'/tmp','meixinji-ui-mcp-qa.png')});
 }finally{await browser.close();legacyStream?.end();server.close();server.closeAllConnections?.();}
})().catch(e=>{console.error(e);process.exit(1)});
