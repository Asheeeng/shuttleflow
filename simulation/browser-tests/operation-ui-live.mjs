import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright';

const base='http://127.0.0.1:18080';
const output=process.env.SHUTTLE_BROWSER_OUTPUT;
if(!output||!process.env.SHUTTLE_TEST_ADMIN_PASSWORD)throw new Error('需要本机隔离实例');
await fs.mkdir(output,{recursive:true});
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1440,height:960}});
const page=await context.newPage();
const errors=[];
const verified=[];
page.on('pageerror',err=>errors.push(err.message));
const status=label=>page.locator('[data-status] .op-status-chip').filter({hasText:label});
const command=key=>page.locator(`[data-command="${key}"]`);
const tab=name=>page.getByRole('tab',{name,exact:true});
async function apiView() {
  const response=await context.request.get(base+'/api/operation/view');
  const envelope=await response.json();
  assert.equal(envelope.code,200);
  return envelope.data;
}
async function toggle(key,phase) {
  await command(key).click();
  await status(phase).waitFor({timeout:20000});
}
try {
  await page.goto(base+'/login.html?role=admin&next=simulation.html');
  await page.locator('#identity').fill(process.env.SHUTTLE_TEST_ADMIN_USERNAME);
  await page.locator('#password').fill(process.env.SHUTTLE_TEST_ADMIN_PASSWORD);
  await page.locator('#submit-button').click();
  await status('待运行').waitFor();
  await tab('仿真配置').click();
  await page.locator('[name=scenario]').selectOption('custom');
  await page.locator('[name=policy]').selectOption('fixed');
  for(const [key,value] of Object.entries({passengers:12,minutes:8,speed:8,travel:30,dwell:5}))
    await page.locator(`[name="${key}"]`).fill(String(value));
  await page.locator('[name=count]').fill('2');
  await command('save').click();
  await page.getByText('配置已保存',{exact:true}).waitFor();
  assert.equal((await apiView()).savedConfig.passengers,12);
  assert.equal((await apiView()).savedConfig.fleet[0].count,2);
  await toggle('start','运行中');
  await toggle('pause','已暂停');
  await tab('车辆运行').click();
  await page.getByRole('heading',{name:'逐站运行记录'}).waitFor();
  const paused=await apiView();
  assert.equal(paused.phase,'paused');
  assert.equal(paused.vehicles.length,2);
  assert.ok(await page.locator('[data-fleet-command=fault]').isVisible());
  await toggle('resume','运行中');
  await tab('运营总览').click();
  await page.locator('[data-metric=checkins]').waitFor();
  await page.screenshot({path:path.join(output,'live-running.png')});
  await toggle('stop','已停止');
  await tab('车辆运行').click();
  await page.locator('[data-history]').selectOption(paused.runId);
  await page.getByRole('button',{name:'回放行程'}).waitFor();
  await tab('高级实验').click();
  const preview=page.waitForResponse(response=>response.url().startsWith(base+'/api/admin/assign?'));
  await page.getByRole('button',{name:'执行分配'}).click();
  assert.equal((await (await preview).json()).code,200);
  verified.push('真实管理员登录','配置保存','开始','暂停','继续','停止','车组与历史行程','高级实验原有分配接口');
  assert.deepEqual(errors,[]);
  await fs.writeFile(path.join(output,'live-checks.json'),JSON.stringify({headless:true,verified,errors,runId:paused.runId},null,2)+'\n');
  console.log('真实仿真接口前端验收通过');
} finally {
  await browser.close();
}
