import { test, expect } from '@playwright/test'
async function login(page, role) {
  await page.goto('/login')
  await page.getByRole('button', { name: role, exact: false }).click()
  await page.getByRole('button', { name: '登录系统' }).click()
  await expect(page).toHaveURL(/\/(passenger|driver|admin)/)
}
async function reset(request) {
  let r = await request.get('/api/auth/csrf')
  const csrf = (await r.json()).data
  const headers = { [csrf.headerName]: csrf.token }
  r = await request.post('/api/auth/login', {
    headers,
    data: { username: 'admin', password: 'Demo123456!' },
  })
  expect(r.ok()).toBeTruthy()
  r = await request.post('/api/admin/demo/reset', { headers, data: { confirmation: 'RESET DEMO' } })
  expect(r.ok()).toBeTruthy()
}
test('三端真实联动：候车、到站、ETA与完成', async ({ browser, request }) => {
  await reset(request)
  const contexts = await Promise.all([
    browser.newContext({ viewport: { width: 390, height: 844 } }),
    browser.newContext({ viewport: { width: 390, height: 844 } }),
    browser.newContext({ viewport: { width: 1440, height: 1000 } }),
  ])
  const [p, d, a] = await Promise.all(contexts.map((c) => c.newPage()))
  const errors = []
  for (const page of [p, d, a]) page.on('pageerror', (e) => errors.push(e.message))
  await login(p, '乘客')
  await expect(p.getByRole('heading', { name: '我要乘车' })).toBeVisible()
  await expect(p.locator('.eta-number')).toContainText('7')
  await p.getByRole('button', { name: '提交候车请求' }).click()
  await expect(p.getByRole('button', { name: '确认全部上车' })).toBeVisible()
  await login(a, '管理员')
  await expect(a.locator('.stats-strip')).toContainText('已上报候车人数')
  await expect(a.locator('.stats-strip>div').nth(2).locator('strong')).toHaveText('1人')
  await a.screenshot({
    path: '../docs/screenshots/admin.png',
    fullPage: true,
    animations: 'disabled',
  })
  await login(d, '司机')
  await d.getByRole('spinbutton', { name: '本站下车人数', exact: true }).fill('1')
  await d.getByRole('spinbutton', { name: '本站上车人数', exact: true }).fill('2')
  await d.getByRole('button', { name: '确认到达 B站' }).click()
  await d.getByRole('button', { name: '已停靠，确认上报' }).click()
  await expect(d.locator('.occupancy strong')).toContainText('11')
  await expect(d.getByRole('button', { name: '确认到达 C站' })).toBeVisible()
  await d.screenshot({
    path: '../docs/screenshots/driver-mobile.png',
    fullPage: true,
    animations: 'disabled',
  })
  await p.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(p.locator('.eta-number')).toContainText('3')
  await expect(p.getByRole('button', { name: '确认全部上车' })).toBeVisible()
  await p.screenshot({
    path: '../docs/screenshots/passenger-mobile.png',
    fullPage: true,
    animations: 'disabled',
  })
  await a.getByRole('button', { name: '刷新数据' }).click()
  await expect(a.getByRole('cell', { name: '11 / 20', exact: true })).toBeVisible()
  await p.getByRole('button', { name: '确认全部上车' }).click()
  await p.getByRole('button', { name: '确认', exact: true }).click()
  await expect(p.getByRole('button', { name: '提交候车请求' })).toBeVisible()
  await expect(p.locator('.history-row')).toContainText('已上车')
  await a.getByRole('button', { name: '刷新数据' }).click()
  await expect(a.locator('.stats-strip>div').nth(2).locator('strong')).toHaveText('0人')
  for (const page of [p, d, a])
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBeTruthy()
  expect(errors).toEqual([])
  await Promise.all(contexts.map((c) => c.close()))
})
test('移动端取消候车与前端角色路由保护', async ({ page, request }) => {
  await reset(request)
  await page.setViewportSize({ width: 390, height: 844 })
  await login(page, '乘客')
  await page.goto('/admin/overview')
  await expect(page).toHaveURL(/passenger/)
  await page.getByRole('button', { name: '提交候车请求' }).click()
  await page.getByRole('button', { name: '取消候车', exact: true }).click()
  await page.getByRole('button', { name: '确认', exact: true }).click()
  await expect(page.locator('.history-row')).toContainText('已取消')
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
})
