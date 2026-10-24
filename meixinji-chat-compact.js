/* 仅修改密谈 UI，不改原消息、API 或存储逻辑 */
(function(){
  function boot(){
    const root=document.getElementById('chat-screen');
    if(!root)return;
    const meta=root.querySelector('.chat-screen-topbar .settings-topbar-meta');
    const faceChar=root.querySelector('#chat-header-char-img');
    const faceMe=root.querySelector('#chat-header-user-img');
    const panel=root.querySelector('#chat-identity-panel');
    if(!meta||!panel||meta.querySelector('.xxj-mini-identity'))return;
    const b=document.createElement('button');
    b.type='button';b.className='xxj-mini-identity';b.title='查看人物卡';b.setAttribute('aria-label','查看人物卡');
    const imgA=document.createElement('img'); const imgB=document.createElement('img');
    imgA.alt='角色头像';imgB.alt='我的头像';
    const name=document.createElement('span');name.className='xxj-mini-identity-name';
    b.append(imgA,imgB,name);meta.appendChild(b);
    const popup=document.createElement('div');popup.className='xxj-compact-identity-popup';popup.hidden=true;popup.setAttribute('aria-label','人物卡');
    const contents=document.createElement('div');contents.className='xxj-mini-card-copy';popup.appendChild(contents);
    meta.style.position='relative';meta.appendChild(popup);
    b.addEventListener('click',()=>{popup.hidden=!popup.hidden;if(!popup.hidden){contents.replaceChildren(panel.cloneNode(true));contents.querySelectorAll('[id]').forEach(x=>x.removeAttribute('id'));}});
    document.addEventListener('pointerdown',(ev)=>{if(!meta.contains(ev.target))popup.hidden=true;});
    let queued=false;
    function sync(){
      queued=false;
      for(const [source,target] of [[faceChar,imgA],[faceMe,imgB]]){const src=source?.getAttribute('src'); if(src && !source.classList.contains('is-hidden')){if(target.getAttribute('src')!==src)target.setAttribute('src',src);target.style.display='';}else{target.removeAttribute('src');target.style.display='none';}}
      name.textContent=root.querySelector('#chat-meta-eyebrow')?.textContent||'聊天';
    }
    const observer=new MutationObserver(()=>{if(queued)return;queued=true;requestAnimationFrame(sync)});
    observer.observe(root.querySelector('.chat-screen-topbar'),{subtree:true,attributes:true,childList:true,characterData:true});
    observer.observe(panel,{subtree:true,attributes:true,childList:true,characterData:true});sync();
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',boot,{once:true});else boot();
})();