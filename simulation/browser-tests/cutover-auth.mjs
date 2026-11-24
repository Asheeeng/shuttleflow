import assert from 'node:assert/strict';
import { randomBytes, randomInt } from 'node:crypto';
import { chromium } from 'playwright';

const origin = process.env.SHUTTLE_BROWSER_BASE_URL || 'http://127.0.0.1:18080';
// This smoke test creates an account; refuse public hosts and the main app port.
assert.equal(new URL(origin).origin, 'http://127.0.0.1:18080');
const browser = await chromium.launch({ headless: true });
const login = `${origin}/login.html?role=passenger`;
const password = randomBytes(18).toString('hex');
const phone = `199${randomInt(10000000, 100000000)}`;
const fillRegistration = async (page) => {
  await page.goto(login);
  await page.locator('#register-toggle').click();
  await page.locator('#passenger-name').fill('切换隔离验收');
  await page.locator('#passenger-phone').fill(phone);
  await page.locator('#passenger-password').fill(password);
};
try {
  const first = await browser.newContext();
  const page = await first.newPage();
  const body = { name: '切换隔离验收', phone, password };
  assert.equal((await first.request.post(`${origin}/api/passenger/register`, { data: body })).status(), 401);
  await first.request.get(`${origin}/api/auth/session?guest=true`);
  assert.equal((await first.request.post(`${origin}/api/passenger/register`, { data: body })).status(), 403);
  await first.close();

  const clean = await browser.newContext();
  const registerPage = await clean.newPage();
  await fillRegistration(registerPage);
  let requests = 0;
  registerPage.on('request', (request) => { if (request.url().endsWith('/api/passenger/register')) requests++; });
  await registerPage.locator('#register-form').evaluate(form => {
    form.requestSubmit();
    form.requestSubmit();
  });
  await registerPage.waitForURL('**/passenger.html');
  await registerPage.getByRole('button', { name: '退出', exact: true }).waitFor();
  assert.equal(requests, 1);
  await registerPage.getByRole('button', { name: '退出', exact: true }).click();
  await registerPage.waitForURL('**/index.html');
  await clean.close();

  const again = await browser.newContext();
  const duplicate = await again.newPage();
  await fillRegistration(duplicate);
  await duplicate.locator('#register-button').click();
  await duplicate.locator('#register-notice').getByText(/手机号.*已注册/).waitFor();
  await duplicate.locator('#register-toggle').click();
  await duplicate.locator('#identity').fill(phone);
  await duplicate.locator('#password').fill(password);
  await duplicate.locator('#submit-button').click();
  await duplicate.waitForURL('**/passenger.html');
  await duplicate.getByRole('button', { name: '退出', exact: true }).waitFor();
  await again.close();

  const guest = await browser.newContext();
  const guestPage = await guest.newPage();
  await guestPage.goto(login);
  await guestPage.locator('#guest-link').click();
  await guestPage.waitForURL('**/passenger.html?guest=1');
  await guestPage.getByRole('button', { name: '退出', exact: true }).waitFor({ state: 'hidden' });
  console.log('全新会话：未登录401、缺CSRF 403、注册、防重复提交、退出、重复手机号、登录和游客访问通过');
  await guestPage.evaluate(async () => {
    const session = await window.ShuttleAuth.readSession(true);
    if (!session.user?.guest) throw new Error('expected guest session');
    const ws = new WebSocket(`${location.origin.replace(/^http/, 'ws')}/ws`);
    await new Promise((resolve, reject) => {
      ws.addEventListener('open', () => setTimeout(() => {
        if (ws.readyState !== WebSocket.OPEN) reject(new Error('socket did not remain open'));
        else { resolve(); ws.close(); }
      }, 65000), { once: true });
      ws.addEventListener('error', () => reject(new Error('WebSocket error')), { once: true });
      ws.addEventListener('close', () => reject(new Error('WebSocket closed before 65 seconds')), { once: true });
    });
  });
  console.log('真实 Chromium WebSocket 连续在线 65 秒（本机直连，未经过 VPS Nginx）');
} finally {
  await browser.close();
}
