<script setup>
import Icon from '../components/Icon.vue'
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, showError, time, states, reportStale } from '../api'
import { usePoll } from '../poll'
const task = ref(null),
  demand = ref([]),
  confirmed = ref(true),
  boarded = ref(0),
  alighted = ref(0),
  occupancyBefore = ref(null),
  busy = ref(false)
const inbox = ref([]),
  plans = ref([]),
  routeStops = ref([]),
  acceptLoad = ref(0),
  planStop = ref(null),
  planDown = ref(0),
  now = ref(Date.now())
let timer
onMounted(() => (timer = setInterval(() => (now.value = Date.now()), 1000)))
onUnmounted(() => clearInterval(timer))
const futureStops = computed(() =>
  routeStops.value.filter(
    (s) =>
      s.stopOrder >
      (routeStops.value.find((x) => x.id === task.value?.lastStopId)?.stopOrder ?? -1),
  ),
)
const pending = computed(() => inbox.value.filter((o) => o.status === 'PENDING'))
const secondsLeft = (o) => Math.max(0, Math.ceil((new Date(o.deadlineAt) - now.value) / 1000))
const dispatchStates = {
  PENDING: '待接单',
  ACCEPTED: '执行中',
  REJECTED: '已拒绝',
  TIMED_OUT: '应答超时',
  COMPLETED: '已完成',
  CANCELLED: '已撤回',
}
function jump(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
async function respond(o, accept) {
  let note = ''
  try {
    if (accept)
      await ElMessageBox.confirm(
        `确认车辆已安全停靠在${task.value.lastStopName}，当前载客${acceptLoad.value}人，接受后按原线路前往${o.targetStopName}。`,
        '确认接受支援任务',
        { confirmButtonText: '接受并出发', cancelButtonText: '返回' },
      )
    else {
      const input = await ElMessageBox.prompt(
        '填写无法执行的原因，系统将通知管理员处理。',
        '拒绝任务',
        {
          inputValidator: (v) => !!v?.trim() || '请填写拒绝原因',
          confirmButtonText: '确认拒绝',
          cancelButtonText: '返回',
        },
      )
      note = input.value
    }
  } catch {
    return
  }
  busy.value = true
  try {
    const result = await api.post(`/driver/dispatches/${o.id}/respond`, {
      accept,
      note,
      occupancy: accept ? acceptLoad.value : null,
    })
    ElMessage.success(
      result.status === 'TIMED_OUT'
        ? '任务已超时，请等待管理员处理'
        : accept
          ? '任务已接受，请依次报站'
          : '已拒绝并通知管理员',
    )
    await refresh()
  } catch (e) {
    showError(e)
  } finally {
    busy.value = false
  }
}
async function savePlan() {
  busy.value = true
  try {
    await api.post('/driver/alighting-plans', {
      stopId: planStop.value,
      expectedAlighted: planDown.value,
    })
    ElMessage.success('下客计划已保存，用于预计运力估算')
    await refresh()
  } catch (e) {
    showError(e)
  } finally {
    busy.value = false
  }
}
const { loading, error, updated, refresh } = usePoll(async () => {
  const [t, d] = await Promise.all([api.get('/driver/task'), api.get('/driver/demand')])
  const [messages, p, route] = await Promise.all([
    api.get('/driver/dispatches'),
    api.get('/driver/alighting-plans'),
    api.get('/routes/' + t.routeId),
  ])
  inbox.value = messages
  plans.value = p
  routeStops.value = route.stops
  if (!futureStops.value.some((s) => s.id === planStop.value))
    planStop.value = route.stops.find(
      (s) => s.stopOrder > (route.stops.find((x) => x.id === t.lastStopId)?.stopOrder ?? -1),
    )?.id
  task.value = t
  demand.value = d
})
async function arrive() {
  try {
    await ElMessageBox.confirm(
      `请确认车辆已安全停靠 ${task.value.nextStop.name}，再上报到站信息。`,
      '安全停靠确认',
      { confirmButtonText: '已停靠，确认上报', cancelButtonText: '返回' },
    )
  } catch {
    return
  }
  busy.value = true
  try {
    task.value = await api.post('/driver/arrivals', {
      stopId: task.value.nextStop.id,
      countsConfirmed: confirmed.value,
      boarded: confirmed.value ? boarded.value : null,
      alighted: confirmed.value ? alighted.value : null,
      occupancyBefore:
        confirmed.value && !task.value.occupancyConfirmed ? occupancyBefore.value : null,
    })
    ElMessage.success('到站信息已更新')
    boarded.value = 0
    alighted.value = 0
    occupancyBefore.value = null
    await refresh()
  } catch (e) {
    showError(e)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <main class="driver-app mobile-workspace">
    <div class="page-heading">
      <div>
        <div class="eyebrow">DRIVER / 车辆工作台</div>
        <h1>行车工作台</h1>
        <p>安全停靠后，再进行操作。</p>
      </div>
      <el-button circle aria-label="刷新" :loading="loading" @click="refresh()"
        ><Icon name="refresh"
      /></el-button>
    </div>
    <el-alert
      v-if="error"
      :title="error + '；当前数据可能已过期'"
      type="error"
      :closable="false"
    /><el-skeleton v-if="loading && !task" animated :rows="6" /><el-empty
      v-if="!task && !loading"
      description="暂无绑定的运行任务，请联系管理员"
    />
    <template v-if="task"
      ><a v-if="pending.length" class="task-notification" href="#dispatch-inbox"
        ><Icon name="bell" /><span
          ><b>收到 {{ pending.length }} 条调度任务</b><small>请在截止时间前接受或拒绝</small></span
        ><Icon name="arrow"
      /></a>
      <section id="report" class="driver-vehicle">
        <div class="split">
          <div class="vehicle-code">
            <span class="vehicle-icon"><Icon name="bus" :size="24" /></span>
            <div>
              <small>当前车辆</small>
              <h2>{{ task.vehicleCode }}</h2>
            </div>
          </div>
          <span :class="['status-chip', task.status]">{{ states[task.status] }}</span>
        </div>
        <div class="driver-line">
          {{ task.routeName }}<small>{{ task.direction }}</small>
        </div>
        <div class="occupancy">
          <strong
            >{{ task.occupancy ?? '—' }}<span> / {{ task.capacity }} 人</span></strong
          ><span>{{
            task.occupancyConfirmed ? '最近确认载客人数' : '历史有效人数 · 当前未确认'
          }}</span>
        </div>
        <div class="occupancy-track">
          <i :style="{ width: ((task.occupancy || 0) / task.capacity) * 100 + '%' }"></i>
        </div>
        <div class="split tiny muted">
          <span>人数更新 {{ time(task.occupancyUpdatedAt).slice(-8) }}</span
          ><span>{{
            task.occupancyConfirmed ? '剩余 ' + task.remainingCapacity + ' 座' : '暂不提供剩余容量'
          }}</span>
        </div>
        <div class="driver-position">
          <Icon name="pin" :size="17" /><span
            >最近到站 <b>{{ task.lastStopName }}</b></span
          ><small>{{ time(task.lastReportAt).slice(-8) }}</small>
        </div>
      </section>
      <el-alert
        v-if="task.status === 'RUNNING' && reportStale(task.lastReportAt)"
        title="到站上报已过期，乘客端暂停精确ETA，请安全停靠后及时上报。"
        type="warning"
        :closable="false"
      /><el-alert
        v-if="!task.occupancyConfirmed"
        title="本次人数未确认。保留最近有效数据，下次上报需重新核实。"
        type="warning"
        :closable="false"
      />
      <section class="arrival-workspace" v-if="task.status === 'RUNNING' && task.nextStop">
        <div class="next-station">
          <div>
            <div class="eyebrow">NEXT STOP / 下一站</div>
            <h2>{{ task.nextStop.name }}</h2>
          </div>
          <span class="next-station-arrow"><Icon name="arrow" :size="30" /></span>
        </div>
        <el-form label-position="top"
          ><div class="count-confirm">
            <span>本站上下车人数</span
            ><el-switch v-model="confirmed" active-text="已确认" inactive-text="人数未确认" />
          </div>
          <template v-if="confirmed"
            ><el-form-item v-if="!task.occupancyConfirmed" label="重新核实：本站上下车前载客人数"
              ><el-input-number
                v-model="occupancyBefore"
                :min="0"
                :max="task.capacity"
                aria-label="核实载客人数"
            /></el-form-item>
            <div class="form-grid count-grid">
              <el-form-item label="本站下车人数"
                ><el-input-number
                  v-model="alighted"
                  :min="0"
                  :max="task.capacity"
                  aria-label="本站下车人数" /></el-form-item
              ><el-form-item label="本站上车人数"
                ><el-input-number
                  v-model="boarded"
                  :min="0"
                  :max="task.capacity"
                  aria-label="本站上车人数"
              /></el-form-item>
            </div>
            <div class="calculation-line">
              先下客，后上客
              <span
                >{{ task.occupancyConfirmed ? task.occupancy : (occupancyBefore ?? '?') }} −
                {{ alighted }} + {{ boarded }} =
                <b>{{
                  (task.occupancyConfirmed ? task.occupancy : occupancyBefore || 0) -
                  alighted +
                  boarded
                }}</b></span
              >
            </div></template
          ><el-alert
            v-else
            title="本次只记录到站，保留最近有效人数与更新时间。"
            type="info"
            :closable="false"
          /><el-button
            class="full arrival-button"
            type="primary"
            size="large"
            :disabled="
              !!error || (confirmed && !task.occupancyConfirmed && occupancyBefore == null)
            "
            :loading="busy"
            @click="arrive"
            ><Icon name="check" />确认到达 {{ task.nextStop.name }}</el-button
          >
          <p class="safety-note">
            <Icon name="alert" :size="14" />请在车辆完全停稳后上报，严禁行驶中操作。
          </p></el-form
        >
      </section>
      <section class="waiting-card" v-else>
        <el-result
          :icon="task.status === 'RUNNING' ? 'success' : 'info'"
          :title="
            task.status === 'RUNNING'
              ? '已到线路终点'
              : task.status === 'STANDBY'
                ? '当前车辆待命中'
                : '车辆当前不可用'
          "
          :sub-title="
            task.status === 'RUNNING'
              ? '本次单向运行结束，等待后续安排。'
              : task.status === 'STANDBY'
                ? '请查看调度通知，接受任务后按线路依次报站。'
                : '请联系管理员核实当日出勤或检修状态，当前不能报站。'
          "
        />
      </section>
      <section id="dispatch-inbox" class="task-inbox">
        <div class="section-heading">
          <h2><Icon name="bell" :size="18" />调度任务</h2>
          <span class="small-badge">{{ inbox.length }} 条</span>
        </div>
        <el-empty v-if="!inbox.length" :image-size="55" description="暂无调度通知" />
        <article
          v-for="o in inbox.slice(0, 8)"
          :key="o.id"
          :class="['driver-order', { pending: o.status === 'PENDING' }]"
        >
          <div class="split">
            <b>支援 {{ o.targetStopName }}</b
            ><span :class="['status-chip', o.status]">{{ dispatchStates[o.status] }}</span>
          </div>
          <p>{{ o.reason }}</p>
          <template v-if="o.status === 'PENDING'"
            ><div class="order-deadline">
              <Icon name="clock" :size="15" />{{
                secondsLeft(o) > 0
                  ? '剩余 ' + secondsLeft(o) + ' 秒应答'
                  : '应答已截止，正在核对状态'
              }}
            </div>
            <label class="accept-load"
              >核实当前载客人数<el-input-number
                v-model="acceptLoad"
                :min="0"
                :max="task.capacity"
                aria-label="接单前载客人数"
            /></label>
            <div class="form-grid">
              <el-button
                type="primary"
                size="large"
                :loading="busy"
                :disabled="!secondsLeft(o)"
                @click="respond(o, true)"
                >接受任务</el-button
              ><el-button
                size="large"
                :disabled="busy || !secondsLeft(o)"
                @click="respond(o, false)"
                >拒绝任务</el-button
              >
            </div></template
          >
          <p v-else class="tiny muted">{{ o.responseNote || '等待后续处理' }}</p>
        </article>
      </section>
      <section class="demand-section">
        <div class="section-heading">
          <h2>前方候车需求</h2>
          <span class="tiny muted">仅系统已上报</span>
        </div>
        <el-empty v-if="!demand.length" :image-size="55" description="没有后续站点" />
        <div v-for="s in demand" :key="s.stopId" class="demand-row">
          <b><Icon name="pin" :size="16" />{{ s.stopName }}</b
          ><span
            ><strong>{{ s.waitingPeople }}</strong> 人
            <small>/ {{ s.requestCount }} 条请求</small></span
          >
        </div>
      </section>
      <section id="alighting-plans" class="plans-section">
        <div class="section-heading">
          <h2>预计下客计划</h2>
          <span class="tiny muted">辅助运力估算</span>
        </div>
        <p class="tiny muted">
          仅填写已核实的后续下车意向。计划不改变实际载客人数，到站时仍需确认。
        </p>
        <div class="plan-form" v-if="futureStops.length">
          <el-select v-model="planStop" aria-label="计划下客站"
            ><el-option
              v-for="s in futureStops"
              :key="s.id"
              :value="s.id"
              :label="s.name" /></el-select
          ><el-input-number
            v-model="planDown"
            :min="0"
            :max="task.capacity"
            aria-label="计划下客人数"
          /><el-button
            :loading="busy"
            :disabled="!planStop || !task.occupancyConfirmed"
            @click="savePlan"
            >保存计划</el-button
          >
        </div>
        <div class="plan-row" v-for="p in plans" :key="p.id">
          <b>{{ routeStops.find((s) => s.id === p.stopId)?.name }}</b
          ><span>预计下客 {{ p.expectedAlighted }} 人</span><small>{{ p.source }}</small>
        </div>
      </section>
    </template>
    <p class="sync-line">每10秒同步 · {{ time(updated) }}</p>
    <nav class="mobile-nav" aria-label="司机快捷导航">
      <button class="active" @click="jump('report')"><Icon name="bus" />到站上报</button
      ><button @click="jump('dispatch-inbox')">
        <Icon name="bell" />调度通知 <em v-if="pending.length">{{ pending.length }}</em></button
      ><button @click="jump('alighting-plans')"><Icon name="users" />下客计划</button>
    </nav>
  </main>
</template>
