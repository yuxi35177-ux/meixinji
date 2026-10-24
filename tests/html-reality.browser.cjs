const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs=require('node:fs/promises'),http=require('node:http'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..');
(async()=>{
 const server=http.createServer(async(req,res)=>{try{let url=new URL(req.url,'http://localhost');let p=path.join(root,decodeURIComponent(url.pathname==='/'?'/index.html':url.pathname));if(!p.startsWith(root+'/'))throw Error();const body=await fs.readFile(p);const ext=path.extname(p);res.setHeader('Content-Type',({'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.woff2':'font/woff2','.png':'image/png'})[ext]||'application/octet-stream');res.end(body);}catch{res.writeHead(404);res.end('Not found')}});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const base='http://127.0.0.1:'+server.address().port;
 const browser=await chromium.launch({executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE,headless:true,args:['--no-sandbox','--disable-dev-shm-usage']});
 try{
 const context=await browser.newContext({viewport:{width:360,height:800},deviceScaleFactor:1,isMobile:true,hasTouch:true});
 await context.route('**/*',route=>new URL(route.request().url()).origin===base||route.request().url().startsWith('data:')?route.continue():route.abort());
 const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(base+'/index.html');await page.locator('#boot-splash-enter').click({timeout:30000});
 await page.waitForFunction(()=>typeof window.XXJ_InlineHtml==='object',{timeout:30000});
 const sanity=await page.evaluate(()=>({ready:XXJ_DB.isReady,entry:!!document.getElementById('my-open-reality'),next:document.getElementById('my-open-stickers').nextElementSibling.id,tests:document.querySelectorAll('.reality-test-item').length,root:getComputedStyle(document.documentElement).fontSize}));
 assert.equal(sanity.entry,true);assert.equal(sanity.next,'my-open-reality');assert.equal(sanity.tests,29);console.log('Entry and test list:',sanity);
 await page.evaluate(()=>{openMyScreen()});await page.locator('#my-open-reality').click();await page.locator('#reality-screen').waitFor({state:'visible'});
 await page.locator('#reality-enabled').check();await page.getByRole('button',{name:'测试日期与时间',exact:true}).click();assert.match(await page.locator('#reality-test-time').textContent(),/20\d\d/);
 await page.getByRole('button',{name:'测试电话状态',exact:true}).click();assert.match(await page.locator('#reality-test-phone').textContent(),/本项未开启/);
 await page.evaluate(()=>XXJ_Navigation.goBack());assert.equal(await page.locator('#reality-screen').isVisible(),false);
 await page.evaluate(()=>{openSettings()});
 await page.locator('#settings-theme-mode').selectOption('dark');assert.equal(await page.evaluate(()=>document.body.classList.contains('dark')),true);
 await page.locator('#settings-theme-mode').selectOption('light');assert.equal(await page.evaluate(()=>document.body.classList.contains('dark')),false);
 await page.evaluate(()=>{const r=document.getElementById('settings-font-size');r.value='18';r.dispatchEvent(new Event('input',{bubbles:true}));});assert.equal(await page.evaluate(()=>getComputedStyle(document.documentElement).fontSize),'18px');
 console.log('Theme, existing font preference and internal native-back hook: passed');
 const builtin=await page.evaluate(()=>{const host=document.createElement('div');document.body.appendChild(host);XXJ_InlineHtml.fillInlineHtmlProseEl(host,'<div class="xxj-ih xxj-ih--slip"><b>原有内置卡片</b></div>');const kept=!!host.querySelector('.xxj-ih')&&!host.querySelector('iframe');host.remove();return kept});assert.equal(builtin,true,'existing card styles and interactions retain their renderer');
 const samples=[
  ['mixed','前面的消息 <div style="width:600px;background:#eef;padding:20px"><h3>卡片标题</h3><p>这是一张卡片</p></div> 后面的消息',1],
  ['encoded','你好 &lt;div style="width:280px"&gt;旧的转义卡片&lt;/div&gt; 再见',1],
  ['fenced','消息\n```html\n<div>围栏卡片</div>\n```',1],
  ['full','<!DOCTYPE html><html><head><style>.card{color:green;font-size:20px}</style></head><body><div class="card">完整文档</div></body></html>',1],
  ['malformed','开始 <div style="width:280px;padding:20px;box-shadow:0 5px 15px rgba(0,0,0. <ol style="font-size:14px;line-height:1. <li style="margin-bottom:10px;">问题是什么？ <li>A. <li>B. <li>C. <li>D. 现在回答问题。',1],
  ['multiple','消息1<div>第一张</div>消息2<section>第二张</section>消息3',2],
  ['interactive','<style>input:checked~p{display:block}</style><div><input id="unique" type="checkbox"><label for="unique">打开内容</label><p style="display:none">隐藏的互动内容</p><details><summary>展开更多</summary><p>一整段文字。'.repeat(1)+'</p></details></div>',1],
  ['security','<div onclick="window.parent.HACKED=true"><script>parent.HACKED=true</script><p>安全卡片</p><img src="x" onerror="parent.HACKED=true"><a href="javascript:parent.HACKED=true">链接</a></div>',1]
 ];
 for(const [name,raw,count] of samples){
  const result=await page.evaluate(({raw})=>{const m={role:'assistant',content:raw};XXJ_InlineHtml.materializeInlineHtmlOnMessage(m);const first=JSON.stringify(m);XXJ_InlineHtml.materializeInlineHtmlOnMessage(m);return {cards:m.inlineHtmlCards?.length||0,content:m.content,original:m.originalHtmlContent,stable:first===JSON.stringify(m)}},{raw});
  assert.equal(result.cards,count,name);assert.equal(result.original,raw,name+' preserves source');assert.equal(result.stable,true,name+' idempotent');
  console.log('Legacy HTML:',name,'=>',result.cards,'cards');
 }
 await page.evaluate(()=>{closeSettings();const host=document.createElement('div');host.id='card-test-host';host.style.cssText='position:fixed;inset:20px;z-index:99999;background:#fff;overflow:auto';document.body.appendChild(host);window.HACKED=false;XXJ_Cards.mount(host,'<div style="width:600px;padding:20px;background:#ddebdc;font-size:18px"><h3>自适应 HTML 卡片</h3><p>长文本应换行，不超出手机宽度。</p><details><summary>展开</summary><p>'+('展开内容，保留中文并调整卡片高度。'.repeat(20))+'</p></details><script>parent.HACKED=true</script></div>')});
 await page.waitForFunction(()=>document.querySelector('#card-test-host iframe')?.contentDocument?.body?.textContent.includes('自适应'));
 await page.waitForTimeout(150);
 const card=await page.evaluate(()=>{const f=document.querySelector('#card-test-host iframe');return {outer:f.getBoundingClientRect().width,inner:f.contentDocument.body.getBoundingClientRect().width,height:parseFloat(f.style.height),scroll:f.contentDocument.documentElement.scrollHeight,hacked:HACKED,script:f.contentDocument.querySelectorAll('script').length}});
 assert.equal(card.hacked,false);assert.equal(card.script,0);assert(card.inner<=card.outer+2,'card fits 320px');assert(card.height<500,'card does not expand forever');
 await page.frameLocator('#card-test-host iframe').locator('summary').click();await page.waitForTimeout(100);
 const expanded=await page.locator('#card-test-host iframe').evaluate(f=>parseFloat(f.style.height));assert(expanded>card.height,'details resize');
 await page.waitForTimeout(400);
 const stableHeight=await page.locator('#card-test-host iframe').evaluate(f=>parseFloat(f.style.height));
 assert(Math.abs(stableHeight-expanded)<=3,'iframe resize must settle, with no observer loop');
 for(const width of [280,412]){await page.setViewportSize({width,height:800});await page.waitForTimeout(80);const fit=await page.locator('#card-test-host iframe').evaluate(f=>({frame:f.clientWidth,body:f.contentDocument.body.getBoundingClientRect().width}));assert(fit.body<=fit.frame+3,'card fits viewport '+width)}
 await page.setViewportSize({width:360,height:800});
 console.log('Responsive card and security:',card,'expanded:',expanded);
 await page.screenshot({path:path.join(process.env.QA_OUTPUT_DIR || '/tmp','meixinji-html-qa.png')});
 await page.evaluate(()=>document.getElementById('card-test-host').remove());
 await page.evaluate(()=>{closeSettings();openMyScreen();XXJ_REALITY.open()});await page.screenshot({path:path.join(process.env.QA_OUTPUT_DIR || '/tmp','meixinji-reality-qa.png')});
 const privacy=await page.evaluate(()=>{
  const c=XXJ_REALITY.config; for(const k of Object.keys(c))c[k]=false;
  window.AndroidShell={getPerceptionSnapshot:()=>JSON.stringify({battery:{percent:73,charging:true},network:{type:'wifi',metered:false},steps:3}),getRealtimeSnapshot:()=>JSON.stringify({notifications:[{secret:'excluded-notification'}],grants:{notifications:true}}),getExtendedSnapshot:()=>JSON.stringify({contacts:[{secret:'excluded-contact'}]}),getDeviceInfo:()=>JSON.stringify({model:'test-device'})};
  c.enabled=true;c.battery=true;
  const limited=XXJ_REALITY.getContext();c.enabled=false;const disabled=XXJ_REALITY.getContext();
  return {limited,disabled};
 });
 assert.match(privacy.limited,/73/);assert(!privacy.limited.includes('excluded-'));assert(!privacy.limited.includes('test-device'));assert.equal(privacy.disabled,'');

 const jsonHistory=await page.evaluate(()=>{const raw=JSON.stringify({thinking:'keep-metadata',lines:['前文 <div>历史 JSON 卡片</div> 后文']});const m={role:'assistant',content:raw};XXJ_InlineHtml.materializeInlineHtmlOnMessage(m);return {same:m.content===raw,cards:m.inlineHtmlCards.length}});
 assert.equal(jsonHistory.same,true);assert.equal(jsonHistory.cards,1);
 const repeatedJsonCards=await page.evaluate(()=>{const m={role:'assistant',content:JSON.stringify({lines:['你好'],inlineHtmlCards:[{html:'<div>JSON 内置卡片</div>'}]})};for(let i=0;i<4;i++)XXJ_InlineHtml.materializeInlineHtmlOnMessage(m);return m.inlineHtmlCards.length});assert.equal(repeatedJsonCards,1,'rendering JSON must not duplicate cards');
 const fallback=await page.evaluate(()=>XXJ_Cards.renderable('<html><body><script>document.write("旧内容")</script></body></html>'));assert.equal(fallback,true,'script-only legacy content remains recoverable');
 const transport=await page.evaluate(async()=>{
  const sent=[];let turn=0;
  window.FloatLocalApi={onmessage:null,postMessage(raw){const m=JSON.parse(raw);sent.push(m.type);if(m.type==='start')setTimeout(()=>this.onmessage({data:JSON.stringify({id:m.id,type:'headers',status:200,contentType:'application/json'})}),0);if(m.type==='pull'){const response=turn++===0?{id:m.id,type:'chunk',data:btoa(unescape(encodeURIComponent('{"message":"本机接口正常"}')))}:{id:m.id,type:'end'};setTimeout(()=>this.onmessage({data:JSON.stringify(response)}),0)}}};
  const response=await fetch('http://127.0.0.1:2048/v1/chat/completions',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"messages":[]}'});const body=await response.json();
  const aborter=new AbortController();const p=fetch('http://127.0.0.1:2048/v1/models',{signal:aborter.signal}).then(()=>false,error=>error.name==='AbortError');aborter.abort();return {body,cancelled:await p,sent};
 });
 assert.equal(transport.body.message,'本机接口正常');assert.equal(transport.cancelled,true);assert(transport.sent.includes('cancel'));console.log('Native transport streaming and cancellation: passed');
 assert.deepEqual(errors,[],'no application JavaScript errors');
 console.log('ALL BROWSER CHECKS PASSED');
 }finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);process.exit(1)});
