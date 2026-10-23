(function(){
'use strict';
if(window.XXJ_REALITY || !window.MeixinjiNative)return;
const KEY='xxj_reality_ai_optin_v1';
let enabled=localStorage.getItem(KEY)==='yes';
function snap(){try{return JSON.parse(window.MeixinjiNative.snapshot())}catch(e){return {error:'设备状态不可用'}}}
function context(){
  let s=snap(), d=new Date(s.time||Date.now());
  let lines=['当前设备日期时间：'+d.toLocaleString('zh-CN',{hour12:false}),
    '时区：'+(s.timezone||'未知'), '电量：'+(s.batteryPercent??'未知')+'%',
    '充电：'+(s.charging?'是':'否'),
    '网络：'+(s.networkType||'未知')];
  if(s.location?.granted && Number.isFinite(s.location.latitude) && Number.isFinite(s.location.longitude)){
    lines.push('设备最近一次已授权位置（可能过期，非实时）：'+s.location.latitude.toFixed(3)+','+s.location.longitude.toFixed(3));
  }
  return '【本地设备感知信息，仅作角色扮演参考；不要推断未提供的事实】\\n'+lines.join('\\n');
}
function updatePanel(){
 const panel=document.getElementById('xxj-reality-panel'); if(!panel)return;
 const s=snap();const p=panel.querySelector('#xxj-reality-stats');
 p.textContent='时间：'+new Date(s.time||Date.now()).toLocaleString('zh-CN')+
 '\\n电量：'+(s.batteryPercent??'未知')+'% · '+(s.charging?'充电中':'未充电')+
 '\\n网络：'+(s.networkType||'未知')+
 '\\n位置：'+(s.location?.granted?'已授权（可能无定位缓存）':'未授权');
 panel.querySelector('#xxj-reality-opt').checked=enabled;
}
function install(){
 if(document.getElementById('xxj-reality-open'))return;
 const css=document.createElement('style');css.textContent=
 '#xxj-reality-open{position:fixed;right:12px;bottom:105px;z-index:9998;border:0;border-radius:24px;padding:9px 12px;background:#437968;color:white;font:13px sans-serif;box-shadow:0 2px 12px #0005}'+
 '#xxj-reality-panel{position:fixed;right:12px;bottom:150px;z-index:9999;max-width:min(310px,90vw);padding:16px;background:var(--bg-card,#fff);color:var(--text,#222);border:1px solid #8885;border-radius:16px;box-shadow:0 8px 30px #0004;font:14px/1.6 sans-serif;white-space:pre-line}'+
 '#xxj-reality-panel button{margin:8px 7px 2px 0;padding:7px;border-radius:8px}';
 document.head.appendChild(css);
 let b=document.createElement('button');b.id='xxj-reality-open';b.textContent='感知';document.body.appendChild(b);
 let p=document.createElement('section');p.id='xxj-reality-panel';p.hidden=true;
 p.innerHTML='<strong>本地现实感知</strong><div id="xxj-reality-stats"></div>'+
 '<label style="display:block;margin-top:8px"><input id="xxj-reality-opt" type="checkbox"> 允许将设备状态发送给当前 AI 模型</label>'+
 '<small>默认关闭。开启后，发送聊天请求时会附加设备状态；定位需单独授权，模型服务可能在云端。</small><div><button id="xxj-reality-loc">授权定位</button><button id="xxj-reality-close">关闭</button></div>';
 document.body.appendChild(p);
 b.onclick=()=>{p.hidden=!p.hidden;if(!p.hidden)updatePanel()};
 p.querySelector('#xxj-reality-close').onclick=()=>p.hidden=true;
 p.querySelector('#xxj-reality-opt').onchange=e=>{enabled=e.target.checked;localStorage.setItem(KEY,enabled?'yes':'no')};
 p.querySelector('#xxj-reality-loc').onclick=()=>window.MeixinjiNative.askForLocation();
 window.addEventListener('meixinji-location-updated',updatePanel);
}
const oldFetch=window.fetch.bind(window);
window.fetch=function(input,init){
 try{
   if(enabled && init?.body && typeof init.body==='string'){
     const url=String(typeof input==='string'?input:input?.url||'');
     if(/\\/chat\\/completions(?:\\?|$)/.test(url)){
       const body=JSON.parse(init.body);
       if(Array.isArray(body.messages) && body.messages.some(x=>x.role==='system')){
         body.messages=[{role:'system',content:context()},...body.messages];
         init={...init,body:JSON.stringify(body)};
       }
     }
   }
 }catch(e){}
 return oldFetch(input,init);
};
window.XXJ_REALITY={snapshot:snap,getContext:context,enabled:()=>enabled};
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',install);else install();
})();