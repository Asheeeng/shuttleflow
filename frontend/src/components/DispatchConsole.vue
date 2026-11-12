<script setup>
import Icon from './Icon.vue'
import { time } from '../api'
defineProps({ board: Object, busy: Boolean })
defineEmits(['assign', 'retry', 'cancel', 'resolve'])
const names = {
  PENDING: '待司机接受',
  ACCEPTED: '执行中',
  REJECTED: '司机拒绝',
  TIMED_OUT: '应答超时',
  COMPLETED: '已到达支援站',
  CANCELLED: '已撤回',
}
</script>
<template>
  <section class="dispatch-console">
    <div class="table-heading">
      <div>
        <div class="eyebrow">RULE-BASED ASSISTANCE</div>
        <h2>辅助调度建议</h2>
      </div>
      <span class="subtle-label"><Icon name="task" :size="14" />管理员确认后派发</span>
    </div>
    <el-empty
      v-if="!board.suggestions?.length"
      :image-size="60"
      description="当前暂无需要派车的运力缺口"
    />
    <article class="suggestion-group" v-for="s in board.suggestions" :key="s.stopId">
      <div class="suggestion-title">
        <span class="alert-symbol"><Icon name="alert" /></span>
        <div>
          <h3>{{ s.stopName }} 需要补充运力</h3>
          <p>
            未覆盖缺口 <b>{{ s.gap }} 人</b> · 待命车辆按预计到站时间排序
          </p>
        </div>
      </div>
      <div class="no-candidate" v-if="!s.candidates.length">
        当前没有符合线路、方向、准备时间与容量条件的待命车辆，请调整当日车辆安排。
      </div>
      <article v-for="(c, i) in s.candidates" :key="c.vehicleId" class="candidate-row">
        <div class="candidate-car">
          <Icon name="bus" />
          <div>
            <b>{{ c.vehicleCode }}</b
            ><small>{{ c.driverName }}</small>
          </div>
          <span v-if="i === 0" class="recommend-tag">优先推荐</span>
        </div>
        <div class="candidate-metrics">
          <div>
            <strong>{{ Math.ceil(c.etaSeconds / 60) }}<small>分钟</small></strong
            ><span>预计抵达</span>
          </div>
          <div>
            <strong>{{ c.availableCapacity }}<small>座</small></strong
            ><span>预计可用</span>
          </div>
        </div>
        <el-button type="primary" :loading="busy" @click="$emit('assign', s, c)"
          >确认派单 {{ c.vehicleCode }}<Icon name="arrow" :size="16"
        /></el-button>
        <p class="candidate-reason">{{ c.reason }}</p>
      </article>
    </article>
  </section>
  <section class="dispatch-console dispatch-history">
    <div class="table-heading">
      <h2>调度任务管理</h2>
      <span class="tiny muted">派发 → 司机应答 → 到站完成</span>
    </div>
    <el-empty v-if="!board.tasks?.length" :image-size="55" description="还没有调度任务" />
    <article class="dispatch-order" v-for="o in board.tasks" :key="o.id">
      <div class="split">
        <div class="order-title">
          <span class="order-number">#{{ String(o.id).padStart(3, '0') }}</span
          ><b>{{ o.vehicleCode }} <Icon name="arrow" :size="15" /> {{ o.targetStopName }}</b>
        </div>
        <span :class="['status-chip', o.status]">{{ names[o.status] }}</span>
      </div>
      <div class="order-timeline">
        <span><Icon name="clock" :size="14" />{{ time(o.createdAt) }} 派发</span
        ><span>{{ o.driverName }}</span
        ><span>预计可用 {{ o.plannedCapacity }} 座</span
        ><span v-if="o.previousTaskId">顺延自 #{{ o.previousTaskId }}</span>
      </div>
      <p class="order-reason">{{ o.reason }}</p>
      <div v-if="o.responseNote" :class="['response-note', { escalated: o.escalated }]">
        <Icon :name="o.escalated ? 'alert' : 'check'" :size="16" /><span>{{ o.responseNote }}</span>
      </div>
      <p v-if="o.resolutionNote" class="resolution-note">
        管理员处理：{{ o.resolutionNote }} · {{ time(o.resolvedAt) }}
      </p>
      <div class="order-actions" v-if="o.status === 'PENDING' || o.escalated">
        <span v-if="o.status === 'PENDING'" class="muted tiny"
          >应答截止 {{ time(o.deadlineAt) }}</span
        ><b v-else class="attention-text">需要管理员处理</b
        ><el-button v-if="o.escalated" :loading="busy" @click="$emit('retry', o)"
          >顺延下一候选车</el-button
        ><el-button v-if="o.escalated" :disabled="busy" @click="$emit('resolve', o)"
          >标记已处理</el-button
        ><el-button v-if="o.status === 'PENDING'" :disabled="busy" @click="$emit('cancel', o)"
          >撤回派单</el-button
        >
      </div>
    </article>
  </section>
</template>
