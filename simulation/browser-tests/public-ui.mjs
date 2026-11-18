import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright';

const origin='https://v13.shuttleflow-gzu.duckdns.org';
const {SHUTTLE_V13_ACCESS_PASSWORD:edge,SHUTTLE_V13_ADMIN_PASSWORD:password,SHUTTLE_V13_RUN_ID:run}=process.env;
if(!edge||!password||!/^[a-f0-9]{12}$/.test(run||''))throw new Error('须传入两个凭据及验收运行号');
const dir=process.env.SHUTTLE_BROWSER_OUTPUT||'output/playwright/v13-vps';
await fs.mkdir(dir,{recursive:true,mode:0o700});
const browser=await chromium.launch({headless:true});
try {
  const context=await browser.newContext({httpCredentials:{origin,username:'v13gate',password:edge},
    acceptDownloads:true,viewport:{width:1440,height:960}});
  const page=await context.newPage();
  const errors=[];
  page.on('pageerror',err=>errors.push(err.message));
  await page.goto(origin+'/login.html?role=admin&next=simulation.html');
  await page.locator('#identity').fill('v13admin');
  await page.locator('#password').fill(password);
  await page.locator('#submit-button').click();
  const nav=page.getByRole('tablist',{name:'运营仿真工作台'});
  await nav.waitFor();
  const labels=['运营总览','仿真配置','车辆运行','智能调度','策略对比','高级实验'];
  for(const [index,label] of labels.entries()){
    await nav.getByRole('tab',{name:label,exact:true}).click();
    await page.screenshot({path:path.join(dir,`tab-${index+1}.png`),animations:'disabled'});
  }
  await nav.getByRole('tab',{name:'车辆运行'}).click();
  await page.locator('[data-history]').selectOption(run);
  const archiveResult=await page.evaluate(async runId=>(
    await fetch('/api/operation/view?run='+encodeURIComponent(runId))
  ).json(),run);
  assert.equal(archiveResult.code,200);
  const completed=Object.values(archiveResult.data.trips).find(trip=>
    trip.status==='completed'&&trip.stops.filter(stop=>stop.arrived!==null).length>=3);
  assert.ok(completed,'运行档案中必须有已完成行程');
  await page.locator('[name=op-route]').selectOption(completed.lineId+':'+completed.direction);
  await page.locator('[name=op-trip]').selectOption(completed.id);

  await page.getByRole('button',{name:'回放行程',exact:true}).click();
  await page.getByRole('button',{name:'暂停回放',exact:true}).click();
  const next=page.waitForEvent('download');
  await page.getByRole('button',{name:'导出历史原始记录'}).click();
  assert.equal((await next).suggestedFilename(),`shuttleflow-${run}.json`);
  assert.deepEqual(errors,[]);
  console.log('公网六标签与历史回放导出通过');
} finally {
  await browser.close();
}
