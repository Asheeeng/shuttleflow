import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright';

const base = process.env.SHUTTLE_TEST_API_BASE || 'http://127.0.0.1:18080';
const username = process.env.SHUTTLE_TEST_ADMIN_USERNAME;
const password = process.env.SHUTTLE_TEST_ADMIN_PASSWORD;
const output = process.env.SHUTTLE_BROWSER_OUTPUT || path.resolve('output/playwright/v13');
if (!username || !password) throw new Error('缺少本机隔离管理员测试环境变量。');
fs.mkdirSync(output, { recursive: true, mode: 0o700 });

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 1365, height: 900 } });
const page = await context.newPage();
const errors = [];
page.on('pageerror', error => errors.push(String(error.message).slice(0, 300)));
page.on('console', message => {
  if (message.type() === 'error') errors.push(String(message.text()).slice(0, 300));
});

let screenshotPath;
try {
  await page.goto(`${base}/login.html?role=admin&next=simulation.html`, { waitUntil: 'domcontentloaded' });
  await page.locator('#identity').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#submit-button').click();
  await page.waitForURL('**/simulation.html', { timeout: 10000 });
  await page.getByRole('tab',{name:'高级实验'}).click();
  await page.getByText('消融实验', { exact: false }).first().waitFor({ state: 'visible', timeout: 10000 });
  await page.getByText('多车协同分配演示', { exact: false }).first().waitFor({ state: 'visible', timeout: 10000 });
  await page.waitForFunction(() => {
    const card = Array.from(document.querySelectorAll('.card')).find(x => x.innerText.includes('多车协同分配演示'));
    return Boolean(card && card.querySelector('select') && card.querySelector('select').options.length > 1);
  }, null, { timeout: 10000 });

  // 此按钮只调用只读分配预览API，不改变车辆、队列或任务。
  const previewButton = page.getByRole('button', { name: '执行分配' });
  if (await previewButton.count()) {
    const previewResponse = page.waitForResponse(response => {
      try {
        const url = new URL(response.url());
        return url.pathname === '/api/admin/assign' && response.request().method() === 'GET';
      } catch { return false; }
    }, { timeout: 15000 });
    await previewButton.first().click();
    const response = await previewResponse;
    const payload = await response.json();
    if (!response.ok() || payload.code !== 200) throw new Error(`多车只读预览接口失败：HTTP ${response.status()} code ${payload.code}`);
  }
  screenshotPath = path.join(output, 'simulation-workbench.png');
  await page.screenshot({ path: screenshotPath, fullPage: true });
  const pageState = await page.evaluate(() => ({
    title: document.title,
    url: location.pathname,
    viewportWidth: innerWidth,
    documentWidth: document.documentElement.scrollWidth,
    pageHeight: document.documentElement.scrollHeight,
    headings: Array.from(document.querySelectorAll('h1,h2,h3')).map(x => x.textContent.trim()).slice(0, 20),
  }));
  if (pageState.documentWidth > pageState.viewportWidth) {
    throw new Error(`仿真工作台出现横向溢出：${pageState.documentWidth}px > ${pageState.viewportWidth}px`);
  }
  const result = {
    source: 'simulated_local_test_environment',
    headless: true,
    browser: 'Playwright-managed Chromium',
    screenshot: screenshotPath,
    page: pageState,
    pageErrors: errors,
  };
  fs.writeFileSync(path.join(output, 'browser_report.json'), JSON.stringify(result, null, 2), { mode: 0o600 });
  if (errors.length) throw new Error(`页面运行时错误${errors.length}条，详见browser_report.json。`);
  console.log(`无头浏览器验收通过；截图：${screenshotPath}`);
} finally {
  try {
    const session = await context.request.get(`${base}/api/auth/session`);
    const sessionPayload = await session.json();
    const csrf = sessionPayload?.data?.csrfToken;
    if (csrf) await context.request.post(`${base}/api/auth/logout`, {
      data: {}, headers: { 'X-Shuttle-CSRF': csrf },
    });
  } catch {
    // 测试会话在本机独立实例中，截图和页面验收不因注销请求异常而中止。
  }
  await context.close();
  await browser.close();
}
