import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = fileURLToPath(new URL('../../',import.meta.url));
const baseline = process.env.SHUTTLE_UI_BASELINE;
const output = process.env.SHUTTLE_BROWSER_OUTPUT;
if (!baseline||!output) throw new Error('需要改造前的静态资源副本和截图输出目录');
await fs.mkdir(output,{recursive:true});
const fixtureDir=path.join(root,'output/operation-v13/20261118-210407');
const loadJson=async file=>JSON.parse(await fs.readFile(path.join(fixtureDir,file),'utf8'));
const archive=await loadJson('smart-run.json');
const comparisons=await Promise.all(['fixed','threshold','smart'].map(key=>loadJson('comparison-'+key+'.json')));
const history=[...comparisons,archive].map(({runId,phase,metrics,config})=>({runId,phase,metrics,config}));
const fixture={...archive,phase:'running',history,savedConfig:archive.config};
const manual=fixture.vehicles.find(car=>car.manual);
const mime={'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.svg':'image/svg+xml'};
const tabs=['运营总览','仿真配置','车辆运行','智能调度','策略对比','高级实验'];
const tabKeys=['overview','configuration','vehicles','dispatch','comparison','advanced'];
const checks=[];
const browser=await chromium.launch({headless:true});

async function open(directory,width,height,role='ADMIN',initial='running') {
  const context=await browser.newContext({viewport:{width,height},acceptDownloads:true});
  const page=await context.newPage();
  const errors=[];
  const requests=[];
  page.on('pageerror',err=>errors.push(err.message));
  await page.routeWebSocket('**/ws',()=>{});
  await page.route('http://shuttleflow-ui.test/**',async route=>{
    const url=new URL(route.request().url());
    const pathname=url.pathname;
    const respond=data=>route.fulfill({json:{code:200,data}});
    if(pathname==='/api/operation/view') {
      if(initial==='loading') await new Promise(resolve=>setTimeout(resolve,1100));
      if(initial==='idle')return respond({enabled:true,phase:'idle',history:[],savedConfig:archive.config});
      if(initial==='error')return respond({...fixture,phase:'error',error:'测试档案：车辆上报失败'});
      if(role==='PASSENGER')return respond({...fixture,metrics:null,history:null});
      if(role==='DRIVER')return respond({...fixture,vehicles:[manual],metrics:null,history:null});
      if(url.searchParams.has('run'))return respond([archive,...comparisons].find(row=>row.runId===url.searchParams.get('run'))||archive);
      return respond({...fixture,phase:initial==='loading'?'running':initial});
    }
    if(pathname==='/api/admin/operation/control') {
      requests.push(route.request().postDataJSON());
      return respond({});
    }
    const routeMatch=pathname.match(/^\/api\/line\/(\d+)\/stations$/);
    if(routeMatch)return respond(archive.routes[routeMatch[1]+':'+(url.searchParams.get('direction')||'go')]?.stops||[]);
    if(pathname==='/api/auth/session')return respond({authenticated:true,csrfToken:'ui-check-fixture',
      user:{id:1,role,displayName:'联调测试账号'},
      ...(role==='DRIVER'?{driver:{id:1,name:'联调司机',defaultVehicleId:manual.id},vehicle:manual}:{}),
      ...(role==='PASSENGER'?{passenger:{id:1,name:'仿真乘客'}}:{})});
    const lines=[1,2,3].map(id=>({id,lineNo:id,name:archive.routes[id+':go'].name}));
    const stations=[...new Map(Object.values(archive.routes).flatMap(item=>item.stops)
      .map(stop=>[stop.stationId,{id:stop.stationId,name:stop.stationName}])).values()];
    if(pathname==='/api/admin/dashboard')return respond({dispatch:{},vehicles:[],queues:[],alerts:[],tasks:[],lines,stations});
    if(pathname==='/api/lines')return respond(lines);
    if(pathname==='/api/stations')return respond(stations);
    if(pathname==='/api/admin/assign')return respond({waitingCount:0,strategy:'no_vehicle',leftover:0,allocation:[]});
    if(pathname.startsWith('/api/'))return respond([]);
    try {
      const file=path.join(directory,pathname==='/'?'simulation.html':pathname);
      await route.fulfill({body:await fs.readFile(file),contentType:mime[path.extname(file)]||'application/octet-stream'});
    } catch {
      await route.fulfill({status:404,body:''});
    }
  });
  const file={ADMIN:'simulation',DRIVER:'driver',PASSENGER:'passenger'}[role];
  await page.goto('http://shuttleflow-ui.test/'+file+'.html');
  return {context,page,errors,requests};
}

async function widthCheck(page,label) {
  const dims=await page.evaluate(()=>({viewport:innerWidth,document:document.documentElement.scrollWidth}));
  assert.ok(dims.document<=dims.viewport+1,`${label} 横向溢出 ${dims.document} > ${dims.viewport}`);
  checks.push({label,...dims});
}

try {
  for(const [width,height] of [[1440,960],[1920,1080],[390,844]]) {
    const old=await open(baseline,width,height);
    await old.page.locator('[data-metric=checkins]').waitFor();
    await old.page.screenshot({path:path.join(output,`before-${width}.png`)});
    await old.context.close();

    const ui=await open(path.join(root,'frontend'),width,height);
    const {page}=ui;
    const nav=page.getByRole('tablist',{name:'运营仿真工作台'});
    await nav.waitFor();
    assert.deepEqual(await nav.getByRole('tab').allTextContents(),tabs);
    assert.ok((await page.locator('[data-metric=checkins]').innerText()).startsWith(String(archive.metrics.checkins)));
    assert.equal(await page.locator('.op-metrics .sf-kpi-card').count(),6);
    assert.equal(await page.locator('[data-tab=overview]').evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(255, 212, 77)');
    for(const [i,label] of tabs.entries()) {
      await nav.getByRole('tab',{name:label,exact:true}).click();
      assert.equal(await nav.getByRole('tab',{name:label}).getAttribute('aria-selected'),'true');
      const id=await nav.getByRole('tab',{name:label}).getAttribute('aria-controls');
      assert.ok(await page.locator('#'+id).isVisible(),label);
      await widthCheck(page,`${width}px ${label}`);
      await page.screenshot({path:path.join(output,`after-${width}-${tabKeys[i]}.png`),animations:'disabled'});
    }
    await nav.getByRole('tab',{name:'运营总览'}).focus();
    await page.keyboard.press('ArrowRight');
    assert.equal(await nav.getByRole('tab',{name:'仿真配置'}).getAttribute('aria-selected'),'true');
    assert.ok(await nav.getByRole('tab',{name:'仿真配置'}).evaluate(el=>el===document.activeElement));
    await page.locator('button[data-command=save]').click();
    assert.equal(ui.requests.at(-1).action,'save');
    assert.equal(ui.requests.at(-1).config.passengers,100);
    assert.equal(ui.requests.at(-1).config.fleet[0].capacity,16);
    await nav.getByRole('tab',{name:'车辆运行'}).click();
    await page.locator('[data-history]').selectOption(archive.runId);
    await page.getByRole('heading',{name:'逐站运行记录'}).waitFor();
    await page.getByRole('button',{name:'回放行程'}).click();
    await page.getByRole('button',{name:'暂停回放'}).waitFor();
    await page.getByRole('button',{name:'暂停回放'}).click();
    await page.screenshot({path:path.join(output,`after-${width}-history.png`),fullPage:true});
    const download=page.waitForEvent('download');
    await page.getByRole('button',{name:'导出历史原始记录'}).click();
    assert.equal((await download).suggestedFilename(),`shuttleflow-${archive.runId}.json`);
    await nav.getByRole('tab',{name:'高级实验'}).click();
    await page.getByRole('button',{name:'执行分配'}).click();
    await page.getByText('无分配记录',{exact:true}).waitFor();
    assert.deepEqual(ui.errors,[]);
    await ui.context.close();
  }

  for(const phase of ['idle','preparing','paused','completed','stopped','error','loading']) {
    const ui=await open(path.join(root,'frontend'),1440,960,'ADMIN',phase);
    if(phase==='loading')await ui.page.getByText('正在加载运营数据…',{exact:true}).waitFor();
    else await ui.page.locator('[data-status] .op-status-chip').waitFor();
    if(phase==='error')await ui.page.getByRole('alert').filter({hasText:'测试档案：车辆上报失败'}).waitFor();
    if(phase==='paused')assert.ok(await ui.page.getByRole('button',{name:'继续',exact:true}).isEnabled());
    if(phase==='completed')assert.equal(await ui.page.locator('[data-status] .op-status-chip').evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(182, 228, 91)');
    await ui.page.screenshot({path:path.join(output,`state-${phase}.png`)});
    assert.deepEqual(ui.errors,[]);
    await ui.context.close();
  }

  for(const role of ['ADMIN','DRIVER','PASSENGER']) {
    const width=role==='ADMIN'?1440:390;
    for(const [stage,directory] of [['before',baseline],['after',path.join(root,'frontend')]]) {
      const ui=await open(directory,width,960,role);
      if(role==='ADMIN')await ui.page.goto('http://shuttleflow-ui.test/admin.html');
      await ui.page.locator('[data-operation] h2').waitFor();
      if(stage==='after')await widthCheck(ui.page,`${width}px ${role}`);
      await ui.page.screenshot({path:path.join(output,`${stage}-role-${role.toLowerCase()}.png`)});
      assert.deepEqual(ui.errors,[]);
      await ui.context.close();
    }
  }
  await fs.writeFile(path.join(output,'layout-checks.json'),JSON.stringify({headless:true,fixtureRun:archive.runId,checks},null,2)+'\n');
  console.log(`同档案前后对比通过，${checks.length} 项宽度检查`);
} finally {
  await browser.close();
}
