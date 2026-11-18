import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright';

const BASE = 'http://127.0.0.1:18080';
const output = process.env.SHUTTLE_BROWSER_OUTPUT;
if (!output || !process.env.SHUTTLE_TEST_ADMIN_PASSWORD) throw new Error('需要独立仿真运行环境');
await fs.mkdir(output, {recursive:true});
const browser = await chromium.launch({headless:true});
const errors = [];
const frames = [];
const verified = [];
const makePage = async width => {
  const context = await browser.newContext({viewport:{width,height:950}});
  const page = await context.newPage();
  page.on('pageerror',error=>errors.push(error.message));
  page.on('websocket',socket=>socket.on('framereceived',({payload})=>{
    try {if(JSON.parse(payload).type==='simulation_change')frames.push(1);} catch{}
  }));
  return page;
};
async function api(page, url, data, expected = 200) {
  const result = await page.evaluate(async ({url,data})=>{
    const res=await fetch(url,data==null?{}:{method:'POST',headers:{'Content-Type':'application/json','X-Shuttle-CSRF':window.SHUTTLE_CSRF||''},body:JSON.stringify(data)});
    return res.json();
  },{url,data});
  assert.equal(result.code,expected,result.msg);
  return result.data;
}
const view = page=>api(page,'/api/operation/view');
const command = (page,action,rest={})=>api(page,'/api/admin/operation/control',{action,...rest});
const delay = ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(check, label, timeout=120000) {
  const deadline=Date.now()+timeout;
  while(Date.now()<deadline) {
    const result=await check();
    if(result)return result;
    await delay(400);
  }
  throw new Error('等待超时 '+label);
}
async function login(page, role, identity, password, next) {
  await page.goto(`${BASE}/login.html?role=${role}${next?'&next='+next:''}`);
  await page.locator('#identity').fill(identity);
  await page.locator('#password').fill(password);
  await page.locator('#submit-button').click();
  await page.waitForURL(`**/${next||role+'.html'}`);
}
async function shot(page, name, panel=false) {
  await page.screenshot({path:path.join(output,name+'.png'),fullPage:true});
  if(panel) {
    const dims=await page.evaluate(()=>({actual:document.documentElement.scrollWidth,viewport:innerWidth}));
    assert.ok(dims.actual<=dims.viewport,`页面横向溢出 ${dims.actual} > ${dims.viewport}`);
  }
}

try {
  const admin=await makePage(1440);
  await login(admin,'admin',process.env.SHUTTLE_TEST_ADMIN_USERNAME,process.env.SHUTTLE_TEST_ADMIN_PASSWORD,'simulation.html');
  const panel=admin.locator('[data-operation=workspace]');
  await panel.getByRole('heading',{name:'运营仿真控制台',exact:true}).waitFor();
  await panel.getByRole('tab',{name:'仿真配置'}).click();
  await panel.locator('[name=scenario]').selectOption('custom');
  const config={
    scenario:'custom', start:'2026-11-18T07:53:00', minutes:55, speed:480,
    seed:20261118, passengers:100, wait:35, reject:true, policy:'smart', flow:'burst',pattern:'mixed',
    fleet:[{lineId:1,direction:'go',count:4,manual:1,capacity:16,initialStation:0,initialLoad:0,headway:110,travel:42,dwell:14}]
  };
  for(const key of ['minutes','speed','seed','passengers','wait','policy','flow','pattern','start']) {
    const node=panel.locator(`[name="${key}"]`);
    const value=String(config[key]);
    if(['policy','flow','pattern'].includes(key))await node.selectOption(value); else await node.fill(value);
  }
  await panel.locator('[name=reject]').check();
  for(const key of ['count','manual','capacity','headway','travel','dwell'])
    await panel.locator(`.op-fleet [name="${key}"]`).fill(String(config.fleet[0][key]));
  await panel.getByRole('button',{name:'保存配置',exact:true}).click();
  await until(async()=> (await view(admin)).savedConfig?.passengers===100,'保存配置');
  await panel.getByRole('button',{name:'开始',exact:true}).click();
  let live = await until(async()=>{
    const s=await view(admin); if(s.phase==='error')throw new Error(s.error);
    return s.phase==='running'&&s.vehicles?.length===4&&s;
  },'100人场景启动');
  await panel.getByRole('button',{name:'暂停',exact:true}).click();
  live=await until(async()=>{const s=await view(admin);return s.phase==='paused'&&s;},'暂停');
  const second=live.second;
  await delay(1100);
  assert.equal((await view(admin)).second,second);
  verified.push('暂停锁定同一模拟时钟');

  const manual=live.vehicles.find(car=>car.manual);
  const creds=await command(admin,'credentials',{vehicleId:manual.id});
  const driver=await makePage(390);
  await login(driver,'driver',creds.phone,creds.password);
  await driver.getByRole('heading',{name:'仿真行程与上下客预测'}).waitFor();
  const driverState=await view(driver);
  assert.equal(driverState.vehicles.length,1);
  assert.equal(driverState.vehicles[0].id,manual.id);
  const vehicle=await api(driver,`/api/driver/vehicle/${manual.id}`);
  assert.equal(vehicle.currentLoad,driverState.vehicles[0].load);
  assert.equal(vehicle.capacity,driverState.vehicles[0].capacity);
  assert.ok(driverState.vehicles[0].predictions.length>0);
  assert.equal((await driver.evaluate(async()=> (await (await fetch('/api/admin/operation/control',{
    method:'POST',headers:{'Content-Type':'application/json','X-Shuttle-CSRF':window.SHUTTLE_CSRF},body:'{"action":"reset"}'
  })).json()).code)),401);

  const passenger=await makePage(390);
  await passenger.goto(BASE+'/login.html?role=passenger');
  const testPhone='194'+Date.now().toString();
  const pass='sf-test-'+Date.now();
  await passenger.locator('#register-toggle').click();
  await passenger.locator('#passenger-name').fill('联调测试乘客');
  await passenger.locator('#passenger-phone').fill(testPhone);
  await passenger.locator('#passenger-password').fill(pass);
  await passenger.locator('#register-form button[type=submit]').click();
  await passenger.waitForURL('**/passenger.html');
  await passenger.getByRole('heading',{name:'仿真车辆位置与到站时间'}).waitFor();
  const stops=live.routes['1:go'].stops;
  const checkin=await api(passenger,'/api/passenger/checkin',{lineId:1,direction:'go',stationId:stops[0].stationId,destStationId:stops[1].stationId});
  assert.ok(checkin.request.id);
  await until(async()=> (await view(admin)).metrics.checkins>=1,'乘客可加入');
  assert.ok((await view(passenger)).vehicles.some(v=>v.plate===manual.plate));
  assert.ok(!('taskHistory' in await view(passenger)));
  const arriveAt = async stop => {
    await driver.locator('select.station-select').first().selectOption(String(stop.stationId));
    await driver.getByRole('button',{name:'确认到站',exact:true}).click();
  };
  await arriveAt(stops[0]);
  await driver.getByRole('button',{name:'无人下车，跳过',exact:true}).click();
  const addBoard = driver.getByRole('button',{name:'确认上客 0 人',exact:true}).locator('xpath=preceding-sibling::div[contains(@class,"count-input")]').last();
  await addBoard.getByRole('button',{name:'+',exact:true}).click();
  await driver.getByRole('button',{name:'确认上客 1 人',exact:true}).click();
  await driver.getByRole('button',{name:'确认离站',exact:true}).click();
  await arriveAt(stops[1]);
  const addAlight = driver.getByRole('button',{name:'确认下客 0 人',exact:true}).locator('xpath=preceding-sibling::div[contains(@class,"count-input")]').last();
  await addAlight.getByRole('button',{name:'+',exact:true}).click();
  await driver.getByRole('button',{name:'确认下客 1 人',exact:true}).click();
  await driver.getByRole('button',{name:'无人上车，跳过',exact:true}).click();
  await driver.getByRole('button',{name:'确认离站',exact:true}).click();
  await until(async()=> (await view(admin)).metrics.alighted>=1,'人工乘客上下车');
  const another=await api(passenger,'/api/passenger/checkin',{lineId:1,direction:'go',stationId:stops[1].stationId,destStationId:stops[2].stationId});
  await api(passenger,'/api/passenger/cancel',{requestId:another.request.id});
  const fleet=live.vehicles.find(v=>!v.manual);
  await command(admin,'fault',{vehicleId:fleet.id});
  await until(async()=> (await view(admin)).vehicles.find(v=>v.id===fleet.id)?.phase==='fault','车辆故障');
  await command(admin,'restore',{vehicleId:fleet.id});
  await until(async()=> (await view(admin)).vehicles.find(v=>v.id===fleet.id)?.phase==='ready','车辆复运');
  const dashboard=await makePage(1440);
  await login(dashboard,'admin',process.env.SHUTTLE_TEST_ADMIN_USERNAME,process.env.SHUTTLE_TEST_ADMIN_PASSWORD);
  await dashboard.getByRole('heading',{name:'运营仿真大屏',exact:true}).waitFor();
  assert.deepEqual((await view(dashboard)).metrics,(await view(admin)).metrics);
  verified.push('真实测试乘客注册签到与取消、绑定司机执行上下车、管理大屏一致、故障与复运、数据按角色过滤');
  await shot(driver,'driver-operation');
  await shot(passenger,'passenger-operation');
  await panel.getByRole('button',{name:'继续',exact:true}).click();

  const final=await until(async()=>{
    const s=await view(admin);
    if(s.phase==='error')throw new Error(s.error);
    return s.phase==='completed'&&s.history.some(h=>h.runId===s.runId)&&s;
  },'智能场景结束');
  assert.equal(final.metrics.checkins,102);
  assert.ok(final.metrics.boarded>0);
  assert.equal(final.metrics.checkins,final.metrics.waiting+final.metrics.cancelled+final.metrics.boarded);
  const history=await api(admin,`/api/operation/view?run=${final.runId}`);
  assert.equal(history.demandPlan.length,100);
  assert.equal(history.passengers.find(r=>r.id===checkin.request.id).tripId,manual.tripId);
  assert.ok(history.passengers.find(r=>r.id===checkin.request.id).alightedAt);
  const manualTrip=history.trips[manual.tripId];
  assert.equal(manualTrip.stops[0].boarded,1);
  assert.equal(manualTrip.stops[1].alighted,1);
  assert.notEqual(manualTrip.stops[0].departed,null);
  assert.equal(manualTrip.stops[0].load,1);
  assert.equal(manualTrip.stops[0].waiting,0);
  assert.ok(history.events.some(e=>e.type==='depart'&&e.tripId));
  assert.ok(history.taskHistory.some(t=>t.action==='rejected'));
  assert.ok(history.taskHistory.some(t=>t.action==='completed'));
  assert.ok(history.taskHistory.some(t=>t.action==='reassigned'));
  assert.ok(frames.length>0);
  const occupancy=history.events.filter(e=>e.afterLoad!=null&&e.vehicleId);
  assert.ok(occupancy.every(e=>e.afterLoad>=0&&e.afterLoad<=16));
  await fs.writeFile(path.join(output,'smart-run.json'),JSON.stringify(history,null,2));
  await fs.writeFile(path.join(output,'experiment-config.json'),JSON.stringify(history.config,null,2));
  await panel.getByRole('tab',{name:'车辆运行'}).click();
  await panel.locator('[data-history]').selectOption(final.runId);
  await panel.getByRole('heading',{name:'逐站运行记录',exact:true}).waitFor();
  await panel.getByRole('button',{name:'回放行程',exact:true}).click();
  await delay(350);
  await shot(admin,'operation-workbench',true);
  await shot(dashboard,'administrator-dashboard',true);
  await panel.getByRole('tab',{name:'高级实验'}).click();
  await admin.getByText('多车协同分配演示',{exact:false}).first().waitFor();
  verified.push('100 名虚拟乘客与两次真人候车请求、方向/载客/行程关联、任务拒绝改派、运行历史回放及 WebSocket');

  // Compare three policies on identical inputs, excluding the manual interventions above.
  const comparison={...config, speed:600, pattern:'auto',reject:false};
  const items=[];
  for(const policy of ['fixed','threshold','smart']) {
    let started;
    await until(async()=>{
      try {started=await command(admin,'start',{config:{...comparison,policy}});return started;}
      catch(error){if(!error.message.includes('已有运营任务'))throw error;}
    },'启动策略 '+policy);
    const run=await until(async()=>{
      const s=await view(admin);
      if(s.phase==='error')throw new Error(s.error);
      return s.runId===started.runId&&s.phase==='completed'&&s.history.some(h=>h.runId===s.runId)&&s;
    },'策略执行 '+policy,180000);
    const result=await api(admin,`/api/operation/view?run=${run.runId}`);
    assert.equal(result.metrics.checkins,100);
    if(items.length)assert.deepEqual(result.demandPlan,items[0].demandPlan);
    items.push(result);
    await fs.writeFile(path.join(output,`comparison-${policy}.json`),JSON.stringify(result,null,2));
  }
  assert.equal(items[0].tasks.length,0);
  assert.ok(items[1].taskHistory.some(t=>t.action==='created'));
  assert.ok(items[2].taskHistory.some(t=>t.action==='created'));
  await until(async()=>{
    try {return await command(admin,'reset');}
    catch(error){if(!error.message.includes('等待清理'))throw error;}
  },'重置当前视图');
  assert.equal((await view(admin)).phase,'idle');
  assert.equal((await api(admin,`/api/operation/view?run=${final.runId}`)).runId,final.runId);
  verified.push('三策略同客流实际回放，重置保留历史');
  assert.deepEqual(errors,[]);
  await fs.writeFile(path.join(output,'browser-report.json'),JSON.stringify({verified,frames:frames.length,errors,run:final.runId,
     comparison:items.map(row=>({runId:row.runId,policy:row.config.policy,metrics:row.metrics}))},null,2));
  console.log('运营工作台无头验收通过',final.metrics,'实时消息',frames.length);
} catch(error) {
  await fs.writeFile(path.join(output,'browser-failure.json'),JSON.stringify({verified,frames:frames.length,errors,error:error.message},null,2));
  throw error;
} finally {
  await browser.close();
}
