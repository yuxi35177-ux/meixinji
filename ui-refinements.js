/* One font scale across every built-in app, including px/vw text. */
(function(){
 'use strict';
 const factor='--xxj-font-scale';
 const seen=new WeakSet();let frame=0;
 function scaleStyle(style){
  const original=style?.getPropertyValue('font-size');
  if(!original||original.includes(factor)||!/\d(?:px|rem|vw|vh|vmin|vmax)\b/.test(original))return;
  const value=original.replace(/([\d.]+)rem\b/g,(_,n)=>(Number(n)*16)+'px');
  style.setProperty('font-size','calc(('+value+') * var('+factor+', .875))',style.getPropertyPriority('font-size'));
 }
 function scanRules(rules){for(const rule of rules||[]){if(rule.selectorText&&!/^(?:html|:root)$/.test(rule.selectorText.trim()))scaleStyle(rule.style);if(rule.cssRules)scanRules(rule.cssRules);}}
 function scanSheet(sheet){if(!sheet||sheet.ownerNode?.id==='xxj-global-typography-style'||seen.has(sheet))return;try{scanRules(sheet.cssRules);seen.add(sheet)}catch{}}
 function inline(root){if(root.nodeType!==1)return;scaleStyle(root.style);for(const el of root.querySelectorAll('[style*="font-size"]'))scaleStyle(el.style);}
 function refresh(){frame=0;for(const sheet of document.styleSheets)scanSheet(sheet);}
 function queue(){if(!frame)frame=requestAnimationFrame(refresh);}
 function install(){
  if(window.AndroidShell)document.body.classList.add('meixinji-native');
  queue();inline(document.body);
  document.addEventListener('load',e=>{if(e.target.tagName==='LINK')scanSheet(e.target.sheet)},true);
  new MutationObserver(records=>{for(const record of records){if(record.target.closest?.('#xxj-global-typography-style'))continue;if(record.target.nodeType===1&&record.target.tagName==='STYLE'){if(record.target.sheet)seen.delete(record.target.sheet);queue();}for(const node of record.addedNodes){if(node.nodeType!==1)continue;if(node.tagName==='STYLE'||node.tagName==='LINK'){if(node.id!=='xxj-global-typography-style')queue();}else inline(node);}if(record.type==='characterData'&&record.target.parentElement?.tagName==='STYLE'&&record.target.parentElement.id!=='xxj-global-typography-style')queue();}}).observe(document.head,{childList:true,subtree:true,characterData:true});
  new MutationObserver(records=>{for(const record of records)for(const node of record.addedNodes)if(node.nodeType===1)inline(node)}).observe(document.body,{childList:true,subtree:true});
  const card=document.querySelector('#chat-screen .chat-identity-wrap');
  if(card){
   const button=document.createElement('button');button.type='button';button.className='xxj-identity-toggle';button.id='chat-identity-toggle';button.setAttribute('aria-controls','chat-identity-panel');
   let collapsed=localStorage.getItem('xxj_identity_collapsed')==='yes';
   function paint(){card.classList.toggle('xxj-identity-collapsed',collapsed);button.textContent=collapsed?'展开人物卡 ▾':'收起 ▴';button.setAttribute('aria-expanded',String(!collapsed));}
   button.onclick=()=>{const scroll=card.closest('.chat-scroll'),before=card.offsetHeight,top=scroll?.scrollTop||0;collapsed=!collapsed;paint();localStorage.setItem('xxj_identity_collapsed',collapsed?'yes':'no');if(scroll&&top>before)scroll.scrollTop=Math.max(0,top+card.offsetHeight-before);};document.getElementById('chat-meta-eyebrow').after(button);paint();
  }
 }
 window.XXJ_UI={refresh:queue};
 if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',install);else install();
})();
