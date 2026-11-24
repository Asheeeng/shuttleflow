import assert from 'node:assert/strict';
import { chromium } from 'playwright';

const origin = 'http://127.0.0.1:18090';
const browser = await chromium.launch({headless:true});
try {
  const page = await browser.newPage({viewport:{width:1440,height:900}});
  const errors=[];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin+'/login.html?role=admin&next=simulation.html');
  await page.locator('#identity').fill(process.env.SHUTTLE_TEST_ADMIN_USERNAME);
  await page.locator('#password').fill(process.env.SHUTTLE_TEST_ADMIN_PASSWORD);
  await page.locator('#submit-button').click();
  await page.waitForURL('**/simulation.html');
  await page.getByText('仅使用虚拟乘客、车辆与时钟，不写正式业务数据。').waitFor();
  for (const tab of ['运营总览','仿真配置','车辆运行','智能调度','策略对比','高级实验']) {
    const item = page.getByRole('tab',{name:tab,exact:true});
    await item.click();
    assert.equal(await item.getAttribute('aria-selected'), 'true');
  }
  await page.getByText(/卡尔曼与多车分配演示保留/).waitFor();
  assert.equal(await page.locator('input[name=manual]:visible').count(),0);
  assert.deepEqual(errors,[]);
  if(process.env.SHUTTLE_BROWSER_OUTPUT)
    await page.screenshot({path:process.env.SHUTTLE_BROWSER_OUTPUT+'/isolated-experiment.png',fullPage:true});
  console.log('无头 Chromium：管理员真实登录，正式模式六个实验标签、隔离说明和高级实验边界通过。');
} finally {
  await browser.close();
}
