<script setup>
import Icon from '../components/Icon.vue'
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, showError, time, states, reportStale } from '../api'
import { usePoll } from '../poll'
const routes = ref([]),
  routeId = ref(null),
  origin = ref(null),
  destination = ref(null),
  party = ref(1),
  history = ref([]),
  eta = ref(null),
  busy = ref(false),
  now = ref(Date.now())
const service = ref({ stations: [], vehicles: [] })
const selectedStation = computed(() =>
  service.value.stations.find((s) => s.stopId === origin.value),
)
function jump(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
let tick,
  etaSequence = 0
const currentRoute = computed(() => routes.value.find((r) => r.id === routeId.value))
const stops = computed(() => currentRoute.value?.stops || [])
const destinationOptions = computed(() =>
  stops.value.filter(
    (s) => s.stopOrder > (stops.value.find((s) => s.id === origin.value)?.stopOrder ?? -1),
  ),
)
const current = computed(() => history.value.find((r) => r.status === 'WAITING'))
const remaining = computed(() =>
  eta.value?.remainingSeconds == null
    ? null
    : Math.ceil(
        (new Date(eta.value.estimatedArrivalAt).getTime() -
          (now.value + (eta.value._offset || 0))) /
          1000,
      ),
)
const waitMinutes = computed(() =>
  current.value
    ? Math.max(0, Math.ceil((new Date(current.value.expiresAt) - now.value) / 60000))
    : 0,
)
async function loadEta() {
  const seq = ++etaSequence
  if (!routeId.value || !origin.value || !destination.value) {
    eta.value = null
    return
  }
  const value = await api.get('/passenger/eta', {
    params: {
      routeId: routeId.value,
      originStopId: origin.value,
      destinationStopId: destination.value,
    },
  })
  if (seq === etaSequence)
    eta.value = { ...value, _offset: new Date(value.serverTime).getTime() - Date.now() }
}
const { loading, error, updated, refresh } = usePoll(async () => {
  if (!routes.value.length) {
    routes.value = await api.get('/routes')
    if (routes.value.length) {
      routeId.value = routes.value[0].id
      origin.value = routes.value[0].stops[2]?.id
      destination.value = routes.value[0].stops[3]?.id
    }
  }
  history.value = await api.get('/passenger/requests')
  if (routeId.value)
    service.value = await api.get('/passenger/service', { params: { routeId: routeId.value } })
  await loadEta()
})
watch(routeId, () => {
  if (!stops.value.some((s) => s.id === origin.value)) {
    origin.value = stops.value[0]?.id
    destination.value = stops.value[1]?.id
  }
})
watch(origin, () => {
  if (!destinationOptions.value.some((s) => s.id === destination.value))
    destination.value = destinationOptions.value[0]?.id
})
watch([routeId, origin, destination], () => {
  eta.value = null
  loadEta().catch(showError)
  if (routeId.value)
    api
      .get('/passenger/service', { params: { routeId: routeId.value } })
      .then((v) => (service.value = v))
      .catch(showError)
})
async function create() {
  busy.value = true
  try {
    await api.post('/passenger/requests', {
      routeId: routeId.value,
      originStopId: origin.value,
      destinationStopId: destination.value,
      partySize: party.value,
    })
    ElMessage.success('候车请求已提交，有效期10分钟')
    await refresh()
    jump('waiting')
  } catch (e) {
    showError(e)
  } finally {
    busy.value = false
  }
}
async function finish(action) {
  try {
    await ElMessageBox.confirm(
      action === 'board'
        ? '确认本请求所有剩余人员均已上车？司机上报不会自动结束您的请求。'
        : '取消本次候车请求？',
      '确认操作',
      { confirmButtonText: '确认', cancelButtonText: '返回', type: 'warning' },
    )
  } catch {
    return
  }
  busy.value = true
  try {
    const r = await api.post(
      `/passenger/requests/${current.value.id}/${action}`,
      action === 'board' ? {} : undefined,
    )
    ElMessage.success(
      r.status === 'EXPIRED' ? '请求已过期' : action === 'board' ? '已确认上车' : '候车请求已取消',
    )
    await refresh()
  } catch (e) {
    showError(e)
  } finally {
    busy.value = false
  }
}
onMounted(
  () =>
    (tick = setInterval(() => {
      now.value = Date.now()
    }, 1000)),
)
onUnmounted(() => clearInterval(tick))
</script>
<template>
  <main class="passenger-app mobile-workspace">
    <div class="page-heading">
      <div>
        <div class="eyebrow">CAMPUS / 乘客出行</div>
        <h1>我要乘车</h1>
        <p>选好站点，下一程更从容。</p>
      </div>
      <el-button circle aria-label="刷新" :loading="loading" @click="refresh()"
        ><Icon name="refresh"
      /></el-button>
    </div>
    <el-alert
      v-if="error"
      :title="error + '；当前显示为上次同步数据'"
      type="error"
      :closable="false"
    />
    <el-skeleton v-if="loading && !routes.length" animated :rows="5" />
    <section id="journey" class="journey-card" v-if="routes.length">
      <div class="line-head">
        <span class="line-symbol"><Icon name="route" /></span
        ><el-select v-model="routeId" aria-label="运行线路"
          ><el-option v-for="r in routes" :key="r.id" :value="r.id" :label="r.name" /></el-select
        ><span class="small-badge">单向</span>
      </div>
      <div class="journey-picker">
        <div>
          <span class="field-caption">从这里上车</span
          ><el-select v-model="origin" aria-label="上车站点"
            ><el-option v-for="s in stops.slice(0, -1)" :key="s.id" :value="s.id" :label="s.name"
          /></el-select>
        </div>
        <Icon name="arrow" :size="22" />
        <div>
          <span class="field-caption">前往目的站</span
          ><el-select v-model="destination" aria-label="目的站点"
            ><el-option v-for="s in destinationOptions" :key="s.id" :value="s.id" :label="s.name"
          /></el-select>
        </div>
      </div>
      <div class="mini-route">
        <button
          v-for="s in stops"
          :key="s.id"
          :disabled="s.stopOrder === stops.length - 1"
          :class="{ selected: s.id === origin, endpoint: s.id === destination }"
          @click="origin = s.id"
        >
          <i></i><span>{{ s.name }}</span
          ><small>{{
            s.stopOrder === 0 ? '起点' : Math.round(s.secondsFromPrevious / 60) + '分钟'
          }}</small>
        </button>
      </div>
      <div class="route-legend">
        <span><i class="live-dot"></i> {{ service.vehicles.length }} 辆运行车辆</span
        ><span>线路示意 · 非实时定位</span>
      </div>
    </section>
    <el-empty v-else-if="!loading" description="暂无可乘坐线路" />
    <section class="eta-panel transit-arrival" aria-live="polite">
      <div class="arrival-top">
        <span
          ><Icon name="clock" :size="16" />
          {{ stops.find((s) => s.id === origin)?.name || '请选择上车站' }} · 下一辆车</span
        ><span class="arrival-pill">到站估算</span>
      </div>
      <template v-if="eta?.status === 'AVAILABLE' && remaining > 0"
        ><div class="eta-number">{{ Math.ceil(remaining / 60) }}<span>分钟后到站</span></div>
        <div class="arrival-details">
          <span><Icon name="bus" :size="17" /> {{ eta.vehicleCode }}</span
          ><span>{{ time(eta.estimatedArrivalAt).slice(-8) }} 预计到达</span>
        </div>
        <div class="arrival-note">
          依据司机报站与模拟站间耗时 · 最近上报 {{ time(eta.lastReportAt).slice(-8) }}
        </div></template
      ><template v-else
        ><h2>
          {{
            eta?.status === 'AVAILABLE' ? '等待司机更新' : eta ? '暂无法估算' : '正在查询到站信息'
          }}
        </h2>
        <p>
          {{
            eta?.status === 'AVAILABLE'
              ? '预计到站时间已过，暂停倒计时。'
              : eta?.message || '请选择有效行程'
          }}
        </p></template
      >
    </section>
    <div v-if="selectedStation?.level === 'HIGH'" class="passenger-notice">
      <Icon name="alert" :size="18" /><span
        >本站已上报需求较多，运营端已提示运力缺口。<small
          >预警不代表已加开车辆，以实际到站为准。</small
        ></span
      >
    </div>
    <section id="waiting" class="waiting-card">
      <div class="section-heading">
        <h2><Icon name="users" :size="18" /> {{ current ? '我的候车' : '一键上报候车' }}</h2>
        <span v-if="current" class="status-chip WAITING">{{
          waitMinutes > 0 ? '候车中' : '待刷新过期状态'
        }}</span
        ><span v-else class="muted tiny">有效期10分钟</span>
      </div>
      <template v-if="current"
        ><div class="waiting-journey">
          {{ current.originName }} <Icon name="arrow" /> {{ current.destinationName }}
        </div>
        <p>{{ current.remaining }} 人候车 · 约 {{ waitMinutes }} 分钟后过期</p>
        <div class="form-grid">
          <el-button size="large" type="primary" :loading="busy" @click="finish('board')"
            >确认全部上车</el-button
          ><el-button size="large" :disabled="busy" @click="finish('cancel')">取消候车</el-button>
        </div>
        <p class="tiny muted waiting-help">司机报站不会自动确认您已上车。</p></template
      ><template v-else
        ><div class="party-row">
          <div><b>同行人数</b><small>请按实际候车人数上报</small></div>
          <el-input-number v-model="party" :min="1" :max="20" aria-label="候车人数" />
        </div>
        <el-button
          class="full primary-cta"
          type="primary"
          size="large"
          :loading="busy || loading"
          :disabled="!origin || !destination || !!error"
          @click="create"
          ><Icon name="pin" :size="18" />提交候车请求</el-button
        >
        <p class="tiny muted waiting-help">
          同一乘客仅保留一条有效请求，超时后可重新提交。
        </p></template
      >
    </section>
    <section class="live-vehicles">
      <div class="section-heading">
        <h2>线路车辆</h2>
        <span class="muted tiny">以司机最近上报为准</span>
      </div>
      <el-empty v-if="!service.vehicles.length" :image-size="55" description="暂无运行车辆" />
      <article v-for="v in service.vehicles" :key="v.vehicleCode" class="vehicle-live-row">
        <span class="vehicle-icon"><Icon name="bus" /></span>
        <div>
          <b>{{ v.vehicleCode }}</b
          ><small>最近上报 {{ v.lastStopName }} · {{ time(v.lastReportAt).slice(-8) }}</small>
        </div>
        <span class="load-status"
          >{{
            reportStale(v.lastReportAt)
              ? '上报已过期'
              : v.occupancyConfirmed
                ? v.occupancy + ' / ' + v.capacity
                : '人数未确认'
          }}<small>{{
            v.occupancyConfirmed && !reportStale(v.lastReportAt) ? '最近载客量' : '暂不判断余座'
          }}</small></span
        >
      </article>
    </section>
    <section id="history" class="section">
      <div class="section-heading">
        <h2>最近候车记录</h2>
        <span class="tiny muted">最近10条</span>
      </div>
      <el-empty v-if="!history.length" :image-size="55" description="还没有候车记录" />
      <div v-for="r in history.slice(0, 10)" :key="r.id" class="history-row">
        <div>
          <b>{{ r.originName }} → {{ r.destinationName }}</b
          ><small>{{ time(r.createdAt) }} · {{ r.partySize }} 人</small>
        </div>
        <span :class="['status-chip', r.status]">{{ states[r.status] }}</span>
      </div>
    </section>
    <p class="sync-line">
      每10秒同步 · {{ time(updated) }}<br />仅统计系统已上报的需求，不代表现场全部候车人数。
    </p>
    <nav class="mobile-nav" aria-label="乘客快捷导航">
      <button @click="jump('journey')"><Icon name="route" />查线路</button
      ><button
        @click="current ? jump('waiting') : create()"
        class="active"
        :disabled="busy || loading || !origin || !destination || !!error"
      >
        <Icon name="pin" />{{ current ? '我的候车' : busy ? '提交中…' : '一键候车' }}</button
      ><button @click="jump('history')"><Icon name="history" />乘车记录</button>
    </nav>
  </main>
</template>
