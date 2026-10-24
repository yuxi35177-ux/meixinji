const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
function load(name, env) { vm.runInNewContext(fs.readFileSync(path.join(__dirname, '..', name), 'utf8'), env); }
function themeEnv() {
  const listeners = {}, store = new Map(), classes = new Set();
  const select = { value: '' }, media = {matches:true, addEventListener:(_,fn)=>listeners.media=fn};
  const document = {hidden:false, body:{classList:{toggle:(n,v)=>v?classes.add(n):classes.delete(n)}}, documentElement:{style:{}}, querySelector:()=>({setAttribute(){}}), getElementById:()=>select, addEventListener:(n,fn)=>listeners[n]=fn};
  const window = {matchMedia:()=>media, XXJ_DB:{isReady:true,K:{THEME_MODE:'mode'},getKv:k=>store.get(k),setKv:(k,v)=>store.set(k,v)}};
  load('theme.js', {window,document});
  return {theme:window.XXJ_THEME, media,listeners,store,classes,select};
}
test('first launch follows system, then reacts to system changes',()=>{
  const e=themeEnv(); e.listeners.DOMContentLoaded(); assert.equal(e.theme.getMode(),'system'); assert.ok(e.classes.has('dark'));
  e.media.matches=false; e.listeners.media(); assert.ok(!e.classes.has('dark'));
});
test('manual mode persists and system mode can be restored',()=>{
  const e=themeEnv(); e.theme.setMode('light'); assert.equal(e.store.get('mode'),'light'); assert.ok(!e.classes.has('dark'));
  e.listeners.media(); assert.ok(!e.classes.has('dark'));
  e.theme.setMode('dark'); assert.ok(e.classes.has('dark'));
  e.theme.setMode('system'); assert.equal(e.select.value,'system');
  e.media.matches=false; e.listeners.visibilitychange(); assert.ok(!e.classes.has('dark'));
});
function navEnv() {
  const listeners={}, windowEvents={};let clock=1000,level=2,pushes=0;
  const buttons=[0,1].map(i=>({id:`pane${i}-close`,disabled:false,closest:()=>null,getBoundingClientRect:()=>({left:10,top:10,right:70,bottom:40,width:60,height:30}),contains:x=>x===buttons[i],click(){level--;}}));
  const target={scrollWidth:100,clientWidth:100,parentElement:null,closest:()=>null};
  const document={body:{},getElementById:()=>null,querySelectorAll:()=>level?[buttons[level-1]]:[],elementFromPoint:()=>level?buttons[level-1]:null,addEventListener:(n,f)=>listeners[n]=f};
  const history={state:null,pushState(state){this.state=state; pushes++;}};
  const env={document,window:{getSelection:()=>null,addEventListener:(n,f)=>windowEvents[n]=f},history,location:{href:'https://example.test/'},navigator:{},performance:{now:()=>clock},innerWidth:393,innerHeight:852,matchMedia:()=>({matches:true}),getComputedStyle:()=>({overflowX:'visible'})};
  load('navigation.js',env);
  return {listeners,windowEvents,target,get level(){return level},get pushes(){return pushes},advance(){clock+=500},swipe(dx,dy=0){const start={clientX:190,clientY:300};listeners.touchstart({touches:[start],target});listeners.touchmove({touches:[{clientX:190+dx,clientY:300+dy}],cancelable:true,preventDefault(){}});listeners.touchend({changedTouches:[{clientX:190+dx,clientY:300+dy}]});}};
}
test('left then right swipe returns two levels and home stays put',()=>{
  const e=navEnv();e.swipe(-100);assert.equal(e.level,1);e.advance();e.swipe(100);assert.equal(e.level,0);e.advance();e.swipe(-100);assert.equal(e.level,0);
});
test('vertical scroll and editable controls do not navigate',()=>{
  const e=navEnv();e.swipe(20,120);assert.equal(e.level,2);e.target.closest=()=>({});e.swipe(100);assert.equal(e.level,2);
});
test('repeated Android browser back events rearm at every level',()=>{
  const e=navEnv();for(let i=0;i<4;i++){e.windowEvents.popstate();e.advance();}assert.equal(e.level,0);assert.equal(e.pushes,5);
});
test('cancelled touches do not navigate',()=>{
  const e=navEnv();e.listeners.touchstart({touches:[{clientX:190,clientY:300}],target:e.target});e.listeners.touchcancel();e.listeners.touchend({changedTouches:[{clientX:300,clientY:300}]});assert.equal(e.level,2);
});
