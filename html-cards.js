/* Display legacy and new HTML as isolated, responsive cards. No message is discarded. */
(function () {
  'use strict';
  const roots = /<!doctype\s+html\b[^>]*>|<(html|div|section|article|aside|figure|table|ul|ol|details|form|main|header|footer|p|blockquote|style)\b[^>]*>/ig;
  const voids = new Set(['area','base','br','col','embed','hr','img','input','link','meta','param','source','track','wbr']);
  function decode(raw) {
    let s = String(raw ?? '');
    if (/&lt;\s*(?:!doctype|html|div|section|article|table|details|ol|ul)\b/i.test(s)) {
      const textarea = document.createElement('textarea'); textarea.innerHTML = s; s = textarea.value;
    }
    return s.replace(/```(?:html|HTML)?\s*\n?([\s\S]*?)```/g, (_, code) => /<\w[\s>]/.test(code) ? code : _);
  }
  function repair(raw) {
    // Recover an unfinished opening tag before the next tag, including an unclosed style quote.
    return raw.replace(/<([a-z][\w-]*)(\s[^<>]*?)?(?=<\/?[a-z])/gi, (_, tag, attrs = '') => {
      const dq = (attrs.match(/"/g) || []).length % 2;
      const sq = (attrs.match(/'/g) || []).length % 2;
      return '<' + tag + attrs + (dq ? '"' : sq ? "'" : '') + '>';
    });
  }
  function extract(raw) {
    const text = decode(raw), parts = [], cards = [];
    if (text.length > 500000) return { parts: [String(raw)], cards };
    let cursor = 0, scan = 0;
    while (scan < text.length) {
      roots.lastIndex = scan;
      const first = roots.exec(text); if (!first) break;
      const tag = first[1]?.toLowerCase() || 'html';
      const start = first.index;
      // A tag at the end of a truncated opening tag is also recoverable.
      let end = text.length, depth = 0, found = false;
      const tags = /<\/?([a-z][\w-]*)\b[^>]*>/ig; tags.lastIndex = start;
      if (/^<!doctype/i.test(first[0])) tags.lastIndex = roots.lastIndex;
      let token;
      while ((token = tags.exec(text))) {
        if (token[1].toLowerCase() !== tag) continue;
        if (token[0][1] === '/') depth--;
        else if (!voids.has(tag) && !/\/\s*>$/.test(token[0])) depth++;
        if (depth === 0) { end = tags.lastIndex; found = true; break; }
      }
      if (tag === 'style' && found) {
        roots.lastIndex = end; const next = roots.exec(text);
        if (next && !text.slice(end,next.index).trim()) {
          // Keep a style block with its document, so its CSS still applies.
          const rest = extract(text.slice(next.index));
          if (rest.cards.length) {
            const lead = text.slice(start,end) + rest.cards[0].html;
            cards.push({html: repair(lead)});
            cards.push(...rest.cards.slice(1));
            const before = text.slice(cursor,start).trim(); if(before) parts.push(before);
            parts.push(...rest.parts); cursor=text.length; scan=text.length; break;
          }
        }
      }
      const html = repair(text.slice(start,end));
      const doc = new DOMParser().parseFromString(html, 'text/html');
      if (!doc.body.textContent.trim() && !doc.body.querySelector('img,svg,input,details,canvas')) { scan = roots.lastIndex; continue; }
      const before = text.slice(cursor,start).trim(); if(before) parts.push(before);
      cards.push({html}); cursor=end; scan=end;
    }
    const tail=text.slice(cursor).trim(); if(tail) parts.push(tail);
    return {parts: cards.length ? parts : [String(raw)], cards};
  }
  function clean(raw) {
    const doc = new DOMParser().parseFromString(repair(decode(raw)), 'text/html');
    doc.querySelectorAll('script,iframe,frame,frameset,object,embed,base,link,meta,applet').forEach(n=>n.remove());
    for(const el of doc.querySelectorAll('*')) {
      for(const attr of [...el.attributes]) {
        if (/^on/i.test(attr.name) || ['srcdoc','formaction','action','autofocus','contenteditable','nonce'].includes(attr.name)) el.removeAttribute(attr.name);
        if (['href','src','xlink:href','poster'].includes(attr.name) && !/^(?:https:\/\/|data:image\/|#)/i.test(attr.value)) el.removeAttribute(attr.name);
      }
      if(el.style?.fontSize?.endsWith('px')) el.style.fontSize=(parseFloat(el.style.fontSize)/16)+'rem';
      if(el.tagName==='STYLE') el.textContent=el.textContent.replace(/font-size\s*:\s*([\d.]+)px/gi,(_,n)=>'font-size:'+(Number(n)/16)+'rem');
      if(el.tagName==='A') {el.removeAttribute('href'); el.removeAttribute('target');}
      if(el.tagName==='BUTTON') el.setAttribute('type','button');
      if(el.tagName==='FORM') el.removeAttribute('action');
    }
    if (/<script\b/i.test(raw) && !doc.body.textContent.trim() && !doc.body.querySelector('img,svg,input,details')) {
      const details=doc.createElement('details'),summary=doc.createElement('summary'),code=doc.createElement('pre');
      summary.textContent='查看旧卡片原始内容';code.textContent=String(raw);details.append(summary,code);doc.body.replaceChildren(details);
    }
    return doc;
  }
  const renderCache=new Map();let cacheChars=0;
  function hasContent(doc){return !!(doc.body.textContent.trim() || doc.body.querySelector('img,svg,input,details,canvas'));}
  function renderable(raw) {
    const key=String(raw||'');if(renderCache.has(key))return renderCache.get(key);
    const result=hasContent(clean(key));renderCache.set(key,result);cacheChars+=key.length;
    while(renderCache.size>64||cacheChars>1000000){const oldest=renderCache.keys().next().value;cacheChars-=oldest.length;renderCache.delete(oldest);}
    return result;
  }
  const mounted = new Set();
  function mount(el,raw) {
    const doc=clean(raw); if(!hasContent(doc)) return false;
    for(const old of mounted)if(!old.iframe.isConnected){old.dispose?.();mounted.delete(old);}
    const iframe=document.createElement('iframe');
    iframe.className='xxj-html-frame'; iframe.title='HTML 聊天卡片';
    iframe.setAttribute('sandbox','allow-same-origin');
    iframe.setAttribute('referrerpolicy','no-referrer');
    iframe.style.cssText='display:block;border:0;width:100%;max-width:100%;min-width:0;height:160px;background:transparent;color-scheme:normal';
    const font=Number.parseFloat(getComputedStyle(document.documentElement).fontSize)||16;
    const csp="default-src 'none'; style-src 'unsafe-inline'; img-src https: data: blob:; font-src data:; script-src 'none'; form-action 'none'; base-uri 'none'; frame-src 'none'; connect-src 'none'";
    const base=`:root{font-size:${font}px;color-scheme:light}*{box-sizing:border-box}html,body{margin:0;min-width:0;width:100%;height:auto;overflow-wrap:anywhere;font-family:system-ui,sans-serif}body{padding:2px;color:#252525;background:#fafaf7;line-height:1.6}img,video,svg,canvas{max-width:100%!important;height:auto}table{max-width:100%;table-layout:fixed;width:100%}pre{white-space:pre-wrap;overflow-wrap:anywhere}body>*{max-width:100%!important;min-width:0!important}input,button,textarea,select{font:inherit}details{max-width:100%}`;
    iframe.srcdoc='<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="'+csp+'"><style>'+base+'</style>'+[...doc.head.querySelectorAll('style')].map(s=>s.outerHTML).join('')+'</head><body>'+doc.body.innerHTML+'</body></html>';
    el.replaceChildren(iframe); el.classList.add('xxj-html-host');
    let observer,mutation,pendingFrame=0;
    const record={iframe,resize:()=>{},dispose:()=>{observer?.disconnect();mutation?.disconnect();cancelAnimationFrame(pendingFrame);}};
    iframe.addEventListener('load',()=>{
      const d=iframe.contentDocument;if(!d)return;
      function schedule(){if(!pendingFrame)pendingFrame=requestAnimationFrame(resize);}
      function resize(){
        pendingFrame=0;
        if(document.hidden||!iframe.getBoundingClientRect().width)return;
        mutation?.disconnect();
        if(!iframe.isConnected){observer?.disconnect();mutation?.disconnect();mounted.delete(record);return;}
        d.documentElement.style.fontSize=(Number.parseFloat(getComputedStyle(document.documentElement).fontSize)||16)+'px';
        // Keep authored fixed widths readable on narrow screens without horizontal clipping.
        const available=Math.max(100,iframe.clientWidth);
        d.body.style.zoom='1';
        const width=d.body.scrollWidth;
        const scale=width>available+3?available/width:1;
        d.body.style.zoom=String(scale);
        const height=Math.ceil(Math.max(d.body.getBoundingClientRect().height,d.body.scrollHeight*scale))+6;
        const next=Math.min(16000,Math.max(40,height));
        if(Math.abs(parseFloat(iframe.style.height)-next)>2) iframe.style.height=next+'px';
        mutation?.observe(d.body,{attributes:true,childList:true,subtree:true});
      }
      record.resize=schedule; mounted.add(record);resize();
      observer=new ResizeObserver(schedule);observer.observe(d.body);
      mutation=new MutationObserver(schedule);mutation.observe(d.body,{attributes:true,childList:true,subtree:true});
      d.addEventListener('toggle',schedule,true);
      d.querySelectorAll('img').forEach(img=>img.addEventListener('load',resize));
      d.addEventListener('touchstart',event=>{
        if(!event.touches.length || event.target.closest('input,button,textarea,select,label,a,[role="slider"]'))return;
        const t=event.touches[0];iframe.__touch={x:t.clientX,y:t.clientY};
      },{passive:true});
      d.addEventListener('touchend',event=>{const s=iframe.__touch;iframe.__touch=null;const t=event.changedTouches[0];if(s&&t&&Math.abs(t.clientX-s.x)>72&&Math.abs(t.clientX-s.x)>Math.abs(t.clientY-s.y)*1.8)window.XXJ_Navigation?.goBack();},{passive:true});
    });
    return true;
  }
  function refresh(){for(const r of mounted){if(r.iframe.isConnected)r.resize();else mounted.delete(r);}}
  window.addEventListener('resize',refresh);
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh()});
  document.addEventListener('xxj-font-size-changed',refresh);
  window.XXJ_Cards={extract,mount,renderable,refresh};
})();
