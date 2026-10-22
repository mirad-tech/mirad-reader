const fs=require('fs'),path=require('path'),assert=require('assert');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'../..'),dir=path.join(root,'build/verification/font-browser');
fs.mkdirSync(dir,{recursive:true});
const fixtureDir=path.join(__dirname,'fixtures');
const js=fs.readFileSync(path.join(root,'app/src/main/assets/reader/reader.js'),'utf8');
const structural=fs.readFileSync(path.join(root,'app/src/main/assets/reader/reader.css'),'utf8');
const css=(size,baseline)=>fs.readFileSync(path.join(fixtureDir,baseline?'baseline':'',`theme-${size}.css`),'utf8');
(async()=>{
const browser=await chromium.launch({headless:true});const results=[];
try{
 for(const mode of ['PAGED','SCROLL'])for(const limited of [false,true])for(const baseline of [true,false]){
  const page=await browser.newPage({viewport:{width:limited?960:390,height:700}});
  const config=size=>({mode,anim:'NONE',pad:20,padTop:28,padBottom:32,colMax:limited?Math.round(size*20):0});
  const body='山间的风吹过竹林，他继续沿着石阶前行。眼前的路转过山腰，远处传来清晰的钟声。'.repeat(130);
  await page.setContent(`<!doctype html><html><head><style>${structural}</style><style id="mirad-theme">${css(14,baseline)}</style></head><body><div id="mirad-content" data-chapter="1"><p>${body}</p><p>${body}</p></div></body></html>`);
  await page.addScriptTag({content:js});await page.evaluate(c=>{MiradReader.init(c);MiradReader.renderHighlights([{start:1500,end:1510,note:'note'}]);MiradReader.markSearch(1520,1530);MiradReader.markTts(1540,1550);MiradReader.gotoOffset(1800,false);},config(14));
  const states=[];
  for(const size of [14,30,14]){
   const before=await page.evaluate(()=>MiradReader.currentOffset());
   await page.evaluate(({theme,c})=>MiradReader.setThemeCssOnly(theme,c),{theme:css(size,baseline),c:config(size)});
   await page.waitForTimeout(120);
   const s=await page.evaluate(()=>{const p=document.querySelector('p'),r=document.createRange();r.selectNodeContents(p);return {state:JSON.parse(MiradReader.getState()),font:getComputedStyle(p).fontSize,colWidth:getComputedStyle(document.querySelector('#mirad-content')).width,lineRects:r.getClientRects().length,marks:[...document.querySelectorAll('mark')].map(m=>[m.className,m.textContent]),scrollHeight:document.documentElement.scrollHeight};});
   s.requested=size;s.before=before;states.push(s);
   assert.equal(s.marks.length,3,'marks lost');
   assert.equal(s.font,baseline?'16px':`${size}px`);
   assert(s.state.offset<=before,'restored page must contain or precede old anchor');
  }
  if(!baseline){if(!limited)assert.notEqual(states[0].lineRects,states[1].lineRects,'wrapping must change');else assert.notEqual(states[0].colWidth,states[1].colWidth,'colMax must scale with font');assert.equal(states[0].lineRects,states[2].lineRects,'wrapping must restore');assert.equal(states[0].state.pages,states[2].state.pages,'page count must restore');if(!limited)assert(states[1].state.pages>states[0].state.pages);}
  await page.screenshot({path:path.join(dir,`${mode}-${limited?'limited':'phone'}-${baseline?'before':'after'}.png`)});
  results.push({mode,limited,baseline,states});await page.close();
 }
 fs.writeFileSync(path.join(dir,'results.json'),JSON.stringify({browser:browser.version(),results},null,2));console.log(JSON.stringify(results.map(r=>({mode:r.mode,limited:r.limited,baseline:r.baseline,states:r.states.map(s=>({size:s.requested,font:s.font,lines:s.lineRects,pages:s.state.pages,before:s.before,offset:s.state.offset,colWidth:s.colWidth,marks:s.marks.length}))})),null,2));
}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
