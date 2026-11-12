<script setup>
import Icon from '../components/Icon.vue'
import DispatchConsole from '../components/DispatchConsole.vue'
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, showError, time, states, reportStale } from '../api'
import { usePoll } from '../poll'
const route = useRoute(),
  overview = ref(null),
  demand = ref([]),
  vehicles = ref([]),
  arrivals = ref([]),
  requests = ref([]),
  busy = ref(false)
const board = ref({ alerts: [], suggestions: [], tasks: [] }),
  fleet = ref([]),
  edit = ref(null),
  editStatus = ref(''),
  editReason = ref(''),
  editReady = ref(60)
const highAlerts = computed(() => board.value.alerts.filter((a) => a.level === 'HIGH'))
const escalations = computed(() => board.value.tasks.filter((t) => t.escalated))
const pendingTasks = computed(() => board.value.tasks.filter((t) => t.status === 'PENDING').length)
async function mutate(path, body, success, method = 'post') {
  busy.value = true
  try {
    await api[method](path, body)
    ElMessage.success(success)
    await refresh()
  } catch (e) {
    showError(e)
    await refresh()
  } finally {
    busy.value = false
  }
}
async function assign(s, c) {
  try {
    await ElMessageBox.confirm(
      `确认派遣 ${c.vehicleCode} 前往${s.stopName}支援？当前缺口${s.gap}人，预计${Math.ceil(c.etaSeconds / 60)}分钟抵达、可用${c.availableCapacity}座。须司机接受后才计入运行运力。`,
      '确认派单',
      { confirmButtonText: '确认派发', cancelButtonText: '返回' },
    )
  } catch {
    return
  }
  await mutate(
    '/admin/dispatches',
    { routeId: s.routeId, targetStopId: s.stopId, vehicleId: c.vehicleId },
    '任务已派发，等待司机接受',
  )
}
async function retry(o) {
  try {
    await ElMessageBox.confirm(
      '将重新核对需求与候选车辆，派发给下一辆符合条件的待命车。',
      '确认顺延',
      { confirmButtonText: '确认顺延', cancelButtonText: '返回' },
    )
  } catch {
    return
  }
  await mutate(`/admin/dispatches/${o.id}/retry`, {}, '已顺延，等待下一位司机接受')
}
async function resolve(o) {
  let note
  try {
    const input = await ElMessageBox.prompt(
      '记录人工处理结果，例如已电话核实或需求已消失。该操作不会改变车辆运行状态。',
      '标记已处理',
      {
        inputValidator: (v) => !!v?.trim() || '请填写处理结果',
        confirmButtonText: '确认处理',
        cancelButtonText: '返回',
      },
    )
    note = input.value
  } catch {
    return
  }
  await mutate(`/admin/dispatches/${o.id}/resolve`, { note }, '已记录人工处理结果')
}
async function cancel(o) {
  try {
    await ElMessageBox.confirm('撤回后将释放该车的派单占位，司机不能再接受该任务。', '撤回派单', {
      confirmButtonText: '确认撤回',
      cancelButtonText: '返回',
    })
  } catch {
    return
  }
  await mutate(`/admin/dispatches/${o.id}/cancel`, {}, '任务已撤回')
}
async function peak() {
  try {
    await ElMessageBox.confirm(
      '将替换本地演示运营记录，初始化：2辆运行车、2辆待命车、1辆检修车，B站2人、C站30人候车。全部为确定性模拟数据。',
      '初始化课间高峰',
      { confirmButtonText: '初始化高峰场景', cancelButtonText: '返回', type: 'warning' },
    )
  } catch {
    return
  }
  await mutate(
    '/admin/demo/peak',
    { confirmation: 'INIT PEAK' },
    '课间高峰场景已就绪：C站30人、预计运力缺口18人',
  )
}
function editVehicle(v) {
  edit.value = v
  editStatus.value = v.status
  editReason.value = v.reason || ''
  editReady.value = v.readyInSeconds
}
async function saveVehicle() {
  busy.value = true
  try {
    await api.patch('/admin/fleet/' + edit.value.vehicleId, {
      status: editStatus.value,
      reason: editReason.value,
      readyInSeconds: editReady.value,
    })
    ElMessage.success('当日车辆状态已更新')
    edit.value = null
    await refresh()
  } catch (e) {
    showError(e)
  } finally {
    busy.value = false
  }
}
const tabs = [
  ['overview', '运营总览', 'grid'],
  ['alerts', '高峰预警', 'alert'],
  ['dispatches', '调度任务', 'task'],
  ['demand', '站点客流', 'users'],
  ['vehicles', '当日车辆', 'bus'],
  ['requests', '候车记录', 'history'],
  ['arrivals', '到站记录', 'pin'],
]
const tab = computed(() =>
  tabs.some((t) => t[0] === route.params.tab) ? route.params.tab : 'overview',
)
const title = computed(() => tabs.find((t) => t[0] === tab.value)?.[1])
const { loading, error, updated, refresh } = usePoll(async () => {
  const data = await Promise.all(
    [
      '/overview',
      '/demand',
      '/vehicles',
      '/arrivals',
      '/requests',
      '/dispatch-board',
      '/fleet',
    ].map((p) => api.get('/admin' + p)),
  )
  ;[
    overview.value,
    demand.value,
    vehicles.value,
    arrivals.value,
    requests.value,
    board.value,
    fleet.value,
  ] = data
})
async function reset() {
  try {
    await ElMessageBox.prompt(
      '将清空候车与到站记录，车辆回到A站、10/20人。仅限本地演示。输入 RESET DEMO 确认。',
      '重置演示数据',
      {
        inputPattern: /^RESET DEMO$/,
        inputErrorMessage: '请输入 RESET DEMO',
        confirmButtonText: '确认重置',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  busy.value = true
  try {
    await api.post('/admin/demo/reset', { confirmation: 'RESET DEMO' })
    ElMessage.success('演示数据已重置')
    await refresh()
  } catch (e) {
    showError(e)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div class="admin-layout">
    <aside class="sidebar">
      <div class="sidebar-title">调度控制台<span>OPERATIONS CENTER</span></div>
      <nav>
        <RouterLink
          v-for="t in tabs"
          :key="t[0]"
          :to="'/admin/' + t[0]"
          :class="{ active: tab === t[0] }"
          ><Icon :name="t[2]" :size="18" />{{ t[1]
          }}<em v-if="t[0] === 'alerts' && highAlerts.length">{{
            highAlerts.length
          }}</em></RouterLink
        >
      </nav>
      <div class="sidebar-note">
        <span class="live-dot"></span> 本地演示环境<b>规则驱动辅助调度</b>
        <p>所有模拟数据均已标注<br />每10秒同步运营状态</p>
        <span>V0.2 / CAMPUS LINK</span>
      </div>
    </aside>
    <main class="admin-main">
      <div class="admin-breadcrumb">
        校园摆渡 <span>/</span> 调度控制台 <span>/</span> {{ title
        }}<small><i class="live-dot"></i> 后端实时同步</small>
      </div>
      <div class="page-heading">
        <div>
          <div class="eyebrow">CAMPUS SHUTTLE OPERATIONS</div>
          <h1>{{ title }}</h1>
          <p class="muted tiny">
            {{ fleet[0]?.serviceDate || '当日' }} · 最近同步 {{ time(updated) }} ·
            到站统计窗口24小时
          </p>
        </div>
        <div class="actions">
          <el-button :loading="loading" @click="refresh()"
            ><Icon name="refresh" :size="16" />刷新数据</el-button
          ><template v-if="overview?.demoResetEnabled"
            ><el-button :disabled="busy" @click="reset">重置演示数据</el-button
            ><el-button type="primary" :loading="busy" @click="peak"
              ><Icon name="users" :size="16" />初始化课间高峰</el-button
            ></template
          >
        </div>
      </div>
      <el-alert
        v-if="error"
        :title="error + '；当前显示为上次同步快照'"
        type="error"
        :closable="false"
      /><el-skeleton v-if="loading && !overview" animated :rows="8" /><template v-if="overview">
        <section class="stats-strip" v-if="tab === 'overview'">
          <div
            v-for="(s, i) in [
              ['运行车辆', overview.runningVehicles, '辆', 'bus'],
              ['有效候车请求', overview.activeRequests, '条', 'task'],
              ['已上报候车人数', overview.waitingPeople, '人', 'users'],
              ['最近到站事件', overview.recentArrivals, '次', 'pin'],
            ]"
            :key="s[0]"
          >
            <span>{{ s[0] }}<Icon :name="s[3]" :size="19" /></span
            ><strong
              >{{ s[1] }}<small>{{ s[2] }}</small></strong
            >
            <div class="stat-foot">
              {{ ['今日出勤车辆', '未过期候车请求', '仅系统已上报需求', '最近24小时'][i] }}
            </div>
          </div>
        </section>
        <div
          v-if="
            ['overview', 'alerts', 'dispatches'].includes(tab) &&
            (highAlerts.length || escalations.length)
          "
          class="operations-alert"
        >
          <Icon name="alert" :size="22" />
          <div>
            <b
              >{{ highAlerts.length }} 个站点存在运力缺口<span v-if="escalations.length">
                · {{ escalations.length }} 个调度任务需要处理</span
              ></b
            >
            <p>基于有效候车、预计到站与确认人数计算。待接单任务仅预留，不视为已经投入运行。</p>
          </div>
          <RouterLink to="/admin/dispatches">查看任务 <Icon name="arrow" :size="16" /></RouterLink>
        </div>
        <section class="capacity-section" v-if="['overview', 'alerts'].includes(tab)">
          <div class="table-heading">
            <div>
              <div class="eyebrow">DEMAND & CAPACITY</div>
              <h2>站点需求与运力</h2>
            </div>
            <span class="subtle-label"
              >10分钟到站窗口 · 缺口 ≥ {{ board.alerts[0]?.threshold || 5 }} 人触发高峰预警</span
            >
          </div>
          <div class="capacity-grid">
            <article
              v-for="a in board.alerts"
              :key="a.stopId"
              :class="['capacity-stop', { high: a.level === 'HIGH' }]"
            >
              <div class="split">
                <h3><span class="station-dot"></span>{{ a.stopName }}</h3>
                <span :class="['status-chip', a.level]">{{
                  { HIGH: '高峰预警', WATCH: '关注', NORMAL: '运力充足' }[a.level]
                }}</span>
              </div>
              <div class="capacity-numbers">
                <div>
                  <span>有效需求</span><strong>{{ a.demand }}<small>人</small></strong>
                </div>
                <div>
                  <span>预计可承接</span><strong>{{ a.allocated }}<small>人</small></strong>
                </div>
                <div>
                  <span>运力缺口</span
                  ><strong class="gap-number">{{ a.gap }}<small>人</small></strong>
                </div>
              </div>
              <div class="capacity-bar">
                <i
                  :style="{
                    width: (a.demand ? Math.min(100, (a.allocated / a.demand) * 100) : 100) + '%',
                  }"
                ></i>
              </div>
              <p>
                {{ a.vehicles.length }} 辆车预计抵达<span v-if="a.pendingCapacity">
                  · 待接单预留 {{ a.pendingCapacity }} 座</span
                >
              </p>
              <details v-if="a.vehicles.length || a.excluded.length">
                <summary>查看运力计算明细</summary>
                <div class="capacity-detail" v-for="v in a.vehicles" :key="v.vehicleId">
                  <b>{{ v.vehicleCode }}</b
                  ><span
                    >原 {{ v.beforeAlighting }} − 计划下客 {{ v.alighted }} = 释放后
                    {{ v.seatsAfterAlighting }} 座</span
                  ><small>预计上客 {{ v.boarded }} 人，之后载客 {{ v.occupancyAfter }} 人</small>
                </div>
                <p v-for="e in a.excluded" :key="e" class="tiny muted">{{ e }}</p>
              </details>
            </article>
          </div>
          <div class="model-note">
            <Icon name="task" :size="16" /><span
              >先下客后上客，按到站时序分配多车容量；预计下客计划不改变实际载客人数。全部演示耗时与初始化数据均为模拟。</span
            >
          </div>
        </section>
        <DispatchConsole
          v-if="['overview', 'dispatches'].includes(tab)"
          :board="board"
          :busy="busy"
          @assign="assign"
          @retry="retry"
          @cancel="cancel"
          @resolve="resolve"
        />
        <section class="table-section" v-if="tab === 'vehicles'">
          <div class="table-heading">
            <h2>当日出勤与可用状态</h2>
            <span class="tiny muted">{{ fleet[0]?.serviceDate }} · 运行中 / 待命 / 不可用</span>
          </div>
          <el-table :data="fleet" empty-text="暂无车辆"
            ><el-table-column prop="vehicleCode" label="车辆编号" min-width="120" /><el-table-column
              prop="driverName"
              label="绑定司机"
              min-width="150"
            /><el-table-column label="当日状态" min-width="120"
              ><template #default="s"
                ><span :class="['status-chip', s.row.status]">{{
                  states[s.row.status]
                }}</span></template
              ></el-table-column
            ><el-table-column
              prop="stagingStopName"
              label="最近驻车站"
              min-width="120"
            /><el-table-column label="准备时间" min-width="100"
              ><template #default="s"
                >{{ s.row.readyInSeconds / 60 }} 分钟</template
              ></el-table-column
            ><el-table-column prop="reason" label="状态说明" min-width="240" /><el-table-column
              label="操作"
              width="100"
              ><template #default="s"
                ><el-button link type="primary" @click="editVehicle(s.row)"
                  >调整状态</el-button
                ></template
              ></el-table-column
            ></el-table
          >
        </section>
        <section class="table-section" v-if="tab === 'demand'">
          <div class="table-heading">
            <h2>站点客流</h2>
            <span class="muted tiny">仅统计未过期的候车请求剩余人数，不代表现场全部人数</span>
          </div>
          <el-table :data="demand" empty-text="暂无站点需求"
            ><el-table-column prop="stopName" label="站点名称" min-width="100" /><el-table-column
              prop="routeName"
              label="所属线路"
              min-width="180"
            /><el-table-column prop="waitingPeople" label="有效候车人数" min-width="130"
              ><template #default="s"
                ><b class="green">{{ s.row.waitingPeople }} 人</b></template
              ></el-table-column
            ><el-table-column
              prop="requestCount"
              label="有效请求数"
              min-width="120"
            /><el-table-column label="统计时间" min-width="180"
              ><template #default="s">{{ time(s.row.updatedAt) }}</template></el-table-column
            ></el-table
          >
        </section>
        <section class="table-section" v-if="['overview', 'vehicles'].includes(tab)">
          <div class="table-heading">
            <h2>车辆运行</h2>
            <span class="muted tiny">未确认人数保留最近有效值，剩余容量暂停显示</span>
          </div>
          <el-table :data="vehicles" empty-text="暂无车辆"
            ><el-table-column prop="vehicleCode" label="车辆编号" min-width="120" /><el-table-column
              prop="routeName"
              label="线路"
              min-width="180"
            /><el-table-column label="状态" min-width="90"
              ><template #default="s"
                ><el-tag :type="s.row.status === 'RUNNING' ? 'success' : 'info'">{{
                  states[s.row.status]
                }}</el-tag></template
              ></el-table-column
            ><el-table-column
              prop="lastStopName"
              label="最近到站"
              min-width="100"
            /><el-table-column label="载客 / 容量" min-width="160"
              ><template #default="s"
                >{{ s.row.occupancy ?? '—' }} / {{ s.row.capacity
                }}<small v-if="s.row.taskId && !s.row.occupancyConfirmed"
                  >（当前未确认）</small
                ></template
              ></el-table-column
            ><el-table-column label="剩余容量" min-width="100"
              ><template #default="s">{{
                s.row.remainingCapacity ?? '—'
              }}</template></el-table-column
            ><el-table-column label="人数更新时间" min-width="180"
              ><template #default="s">{{
                time(s.row.occupancyUpdatedAt)
              }}</template></el-table-column
            ><el-table-column label="最近到站时间" min-width="180"
              ><template #default="s"
                >{{ time(s.row.lastReportAt)
                }}<small
                  v-if="s.row.status === 'RUNNING' && reportStale(s.row.lastReportAt)"
                  class="stale-label"
                  >上报已过期</small
                ></template
              ></el-table-column
            ></el-table
          >
        </section>
        <section class="table-section" v-if="tab === 'requests'">
          <div class="table-heading">
            <h2>候车请求</h2>
            <span class="muted tiny">最新100条 · 已结束记录保留</span>
          </div>
          <div class="state-counts">
            <span v-for="(n, s) in overview.requestStates" :key="s"
              >{{ states[s] }} <b>{{ n }}</b></span
            >
          </div>
          <el-table :data="requests" empty-text="暂无候车记录"
            ><el-table-column prop="id" label="编号" width="80" /><el-table-column
              label="行程"
              min-width="150"
              ><template #default="s"
                >{{ s.row.originName }} → {{ s.row.destinationName }}</template
              ></el-table-column
            ><el-table-column prop="partySize" label="请求人数" min-width="100" /><el-table-column
              prop="boardedCount"
              label="已上车人数"
              min-width="110"
            /><el-table-column label="状态" min-width="110"
              ><template #default="s"
                ><el-tag :type="s.row.status === 'WAITING' ? 'success' : 'info'">{{
                  states[s.row.status]
                }}</el-tag></template
              ></el-table-column
            ><el-table-column label="创建时间" min-width="180"
              ><template #default="s">{{ time(s.row.createdAt) }}</template></el-table-column
            ><el-table-column label="过期时间" min-width="180"
              ><template #default="s">{{ time(s.row.expiresAt) }}</template></el-table-column
            ></el-table
          >
        </section>
        <section class="table-section" v-if="tab === 'arrivals'">
          <div class="table-heading">
            <h2>最近到站上报</h2>
            <span class="muted tiny">最新50条 · 服务器时间</span>
          </div>
          <el-table :data="arrivals" empty-text="暂无到站记录"
            ><el-table-column prop="vehicleCode" label="车辆" min-width="120" /><el-table-column
              prop="stopName"
              label="站点"
              min-width="100"
            /><el-table-column label="上车 / 下车" min-width="150"
              ><template #default="s"
                >{{ s.row.boarded ?? '未确认' }} / {{ s.row.alighted ?? '未确认' }}</template
              ></el-table-column
            ><el-table-column label="到站后人数" min-width="150"
              ><template #default="s">{{
                s.row.countsConfirmed
                  ? s.row.occupancyAfter
                  : '未确认（历史 ' + s.row.occupancyAfter + '）'
              }}</template></el-table-column
            ><el-table-column label="上报时间" min-width="180"
              ><template #default="s">{{ time(s.row.reportedAt) }}</template></el-table-column
            ></el-table
          >
        </section> </template
      ><el-empty v-else-if="!loading" description="暂未读取到运营数据" /><el-dialog
        :model-value="!!edit"
        title="调整当日车辆状态"
        width="min(480px,92vw)"
        @close="edit = null"
        ><el-form label-position="top"
          ><el-form-item label="车辆"
            ><b>{{ edit?.vehicleCode }} · {{ edit?.serviceDate }}</b></el-form-item
          ><el-form-item label="当日状态"
            ><el-select v-model="editStatus" aria-label="当日状态"
              ><el-option
                v-for="s in ['RUNNING', 'STANDBY', 'UNAVAILABLE']"
                :key="s"
                :value="s"
                :label="states[s]" /></el-select></el-form-item
          ><el-form-item label="准备时间（秒）"
            ><el-input-number v-model="editReady" :min="0" :max="600" :step="60" /></el-form-item
          ><el-form-item label="状态说明（不可用时必填）"
            ><el-input v-model="editReason" type="textarea" maxlength="300" show-word-limit
          /></el-form-item>
          <p class="tiny muted">
            有待接单或执行中任务时禁止调整。启动运行需有效位置上报；过期车辆请通过调度由司机确认出发。
          </p></el-form
        ><template #footer
          ><el-button @click="edit = null">取消</el-button
          ><el-button type="primary" :loading="busy" @click="saveVehicle"
            >保存状态</el-button
          ></template
        ></el-dialog
      >
    </main>
  </div>
</template>
