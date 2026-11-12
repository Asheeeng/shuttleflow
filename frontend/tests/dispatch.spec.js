import { test, expect } from '@playwright/test'
async function login(page, name, role) {
  await page.goto('/login')
  await page.getByRole('button', { name: role, exact: false }).click()
  await page.getByRole('textbox', { name: '用户名', exact: true }).fill(name)
  await page.getByRole('button', { name: '登录系统' }).click()
  await expect(page).toHaveURL(/\/(passenger|driver|admin)/)
}
async function peak(page) {
  await page.getByRole('button', { name: '初始化课间高峰', exact: true }).click()
  await page.getByRole('button', { name: '初始化高峰场景', exact: true }).click()
  await expect(page.locator('.capacity-stop.high')).toContainText('C站')
  await expect(page.locator('.capacity-stop.high .gap-number')).toContainText('18')
}
async function report(page, name) {
  await page.getByRole('button', { name: '确认到达 ' + name }).click()
  await page.getByRole('button', { name: '已停靠，确认上报' }).click()
}
async function fits(page) {
  await expect
    .poll(async () => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    .toBeTruthy()
}
test('高峰调度完整闭环：预警、推荐、拒绝顺延、接受和到站完成', async ({ browser }) => {
  const contexts = await Promise.all([
    browser.newContext({ viewport: { width: 1440, height: 1000 } }),
    browser.newContext({ viewport: { width: 390, height: 844 } }),
    browser.newContext({ viewport: { width: 390, height: 844 } }),
    browser.newContext({ viewport: { width: 390, height: 844 } }),
  ])
  const [a, p, d2, d4] = await Promise.all(contexts.map((c) => c.newPage()))
  const errors = []
  for (const page of [a, p, d2, d4]) page.on('pageerror', (e) => errors.push(e.message))
  await login(a, 'admin', '管理员')
  await peak(a)
  await expect(a.locator('.candidate-row').first()).toContainText('DEMO-02')
  await a.screenshot({
    path: '../docs/screenshots/v02/admin-desktop.png',
    fullPage: true,
    animations: 'disabled',
  })
  await login(p, 'passenger', '乘客')
  await expect(p.locator('.eta-number')).toContainText('3')
  await expect(p.locator('.vehicle-live-row')).toHaveCount(2)
  await expect(p.locator('.passenger-notice')).toContainText('运力缺口')
  await p.screenshot({
    path: '../docs/screenshots/v02/passenger-mobile.png',
    fullPage: true,
    animations: 'disabled',
  })
  await a.getByRole('button', { name: '确认派单 DEMO-02' }).click()
  await a.getByRole('button', { name: '确认派发', exact: true }).click()
  await expect(a.locator('.dispatch-order')).toContainText('待司机接受')
  await login(d2, 'driver2', '司机')
  await expect(d2.locator('.task-notification')).toContainText('收到 1 条调度任务')
  await expect(d2.locator('.driver-order.pending')).toContainText('支援 C站')
  await d2.screenshot({
    path: '../docs/screenshots/v02/driver-inbox-mobile.png',
    fullPage: true,
    animations: 'disabled',
  })
  await d2.getByRole('button', { name: '拒绝任务', exact: true }).click()
  await d2.getByRole('textbox').last().fill('演示：临时车辆检查')
  await d2.getByRole('button', { name: '确认拒绝', exact: true }).click()
  await expect(d2.locator('.driver-order .status-chip')).toHaveText('已拒绝')
  await a.getByRole('button', { name: '刷新数据' }).click()
  await expect(a.locator('.response-note.escalated')).toContainText('临时车辆检查')
  await a.getByRole('button', { name: '顺延下一候选车' }).click()
  await a.getByRole('button', { name: '确认顺延', exact: true }).click()
  await expect(a.locator('.dispatch-order').first()).toContainText('DEMO-04')
  await login(d4, 'driver4', '司机')
  await d4.getByRole('button', { name: '接受任务', exact: true }).click()
  await d4.getByRole('button', { name: '接受并出发', exact: true }).click()
  await expect(d4.locator('.driver-order .status-chip')).toHaveText('执行中')
  await expect(d4.getByRole('button', { name: '确认到达 B站' })).toBeVisible()
  await d4.screenshot({
    path: '../docs/screenshots/v02/driver-mobile.png',
    fullPage: true,
    animations: 'disabled',
  })
  await report(d4, 'B站')
  await expect(d4.getByRole('button', { name: '确认到达 C站' })).toBeVisible()
  await d4.getByRole('spinbutton', { name: '本站上车人数', exact: true }).fill('18')
  await report(d4, 'C站')
  await expect(d4.locator('.driver-order .status-chip')).toHaveText('已完成')
  await expect(d4.locator('.occupancy strong')).toContainText('18')
  await a.getByRole('button', { name: '刷新数据' }).click()
  await expect(a.locator('.dispatch-order').first()).toContainText('已到达支援站')
  await a.getByRole('link', { name: '调度任务', exact: true }).click()
  await a.screenshot({
    path: '../docs/screenshots/v02/dispatch-completed.png',
    fullPage: true,
    animations: 'disabled',
  })
  for (const page of [a, p, d2, d4]) await fits(page)
  expect(errors).toEqual([])
  await Promise.all(contexts.map((c) => c.close()))
})
test('当日车辆管理与窄屏布局', async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } }),
    page = await context.newPage()
  await login(page, 'admin', '管理员')
  await peak(page)
  await page.getByRole('link', { name: '当日车辆', exact: true }).click()
  const row = page.getByRole('row').filter({ hasText: 'DEMO-02' }).filter({ hasText: '备勤司机' })
  await row.getByRole('button', { name: '调整状态' }).click()
  await page
    .getByRole('dialog', { name: '调整当日车辆状态' })
    .locator('.el-select__wrapper')
    .click()
  await page.getByRole('option', { name: '不可用', exact: true }).click()
  await page.getByRole('textbox').last().fill('演示：临时检修')
  await page.getByRole('button', { name: '保存状态' }).click()
  await expect(row).toContainText('不可用')
  await expect(row).toContainText('临时检修')
  await expect(page.getByRole('dialog', { name: '调整当日车辆状态' })).not.toBeVisible()
  await page.setViewportSize({ width: 360, height: 800 })
  await fits(page)
  await page.getByRole('link', { name: '运营总览', exact: true }).click()
  await fits(page)
  await context.close()
})
test('手机底部一键候车与司机下客计划实际保存', async ({ browser, request }) => {
  const csrf = (await (await request.get('/api/auth/csrf')).json()).data,
    headers = { [csrf.headerName]: csrf.token }
  await request.post('/api/auth/login', {
    headers,
    data: { username: 'admin', password: 'Demo123456!' },
  })
  await request.post('/api/admin/demo/reset', { headers, data: { confirmation: 'RESET DEMO' } })
  const pc = await browser.newContext({ viewport: { width: 360, height: 800 } }),
    dc = await browser.newContext({ viewport: { width: 390, height: 844 } }),
    p = await pc.newPage(),
    d = await dc.newPage()
  await login(p, 'passenger', '乘客')
  await expect(p.getByRole('button', { name: '一键候车', exact: true })).toBeVisible()
  await p.getByRole('button', { name: '一键候车', exact: true }).click()
  await expect(p.getByRole('button', { name: '确认全部上车' })).toBeVisible()
  expect((await (await request.get('/api/admin/overview')).json()).data.waitingPeople).toBe(1)
  await p.screenshot({
    path: '../docs/screenshots/v02/passenger-waiting-mobile.png',
    fullPage: true,
    animations: 'disabled',
  })
  await login(d, 'driver', '司机')
  await d.locator('.plan-form .el-select__wrapper').click()
  await d.getByRole('option', { name: 'C站', exact: true }).click()
  await d.getByRole('spinbutton', { name: '计划下客人数', exact: true }).fill('2')
  await d.getByRole('button', { name: '保存计划', exact: true }).click()
  await expect(d.locator('.plan-row')).toContainText('C站')
  await expect(d.locator('.plan-row')).toContainText('预计下客 2 人')
  await fits(p)
  await fits(d)
  await pc.close()
  await dc.close()
})
test('管理员撤回与拒绝任务人工处理留痕', async ({ browser }) => {
  const ac = await browser.newContext({ viewport: { width: 1440, height: 1000 } }),
    dc = await browser.newContext({ viewport: { width: 390, height: 844 } }),
    a = await ac.newPage(),
    d = await dc.newPage()
  await login(a, 'admin', '管理员')
  await peak(a)
  await a.getByRole('button', { name: '确认派单 DEMO-02' }).click()
  await a.getByRole('button', { name: '确认派发', exact: true }).click()
  await a.getByRole('button', { name: '撤回派单', exact: true }).click()
  await a.getByRole('button', { name: '确认撤回', exact: true }).click()
  await expect(a.locator('.dispatch-order').first()).toContainText('已撤回')
  await a.getByRole('button', { name: '确认派单 DEMO-02' }).click()
  await a.getByRole('button', { name: '确认派发', exact: true }).click()
  await login(d, 'driver2', '司机')
  await d.getByRole('button', { name: '拒绝任务', exact: true }).click()
  await d.getByRole('textbox').last().fill('演示：现场无法出车')
  await d.getByRole('button', { name: '确认拒绝', exact: true }).click()
  await a.getByRole('button', { name: '刷新数据' }).click()
  await a.getByRole('button', { name: '标记已处理', exact: true }).click()
  await a.getByRole('textbox').last().fill('已人工核实，安排线下处理')
  await a.getByRole('button', { name: '确认处理', exact: true }).click()
  await expect(a.locator('.resolution-note')).toContainText('已人工核实')
  await expect(a.getByRole('button', { name: '标记已处理', exact: true })).toHaveCount(0)
  await ac.close()
  await dc.close()
})
