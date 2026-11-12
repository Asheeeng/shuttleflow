<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { login, home, showError } from '../api'
const form = reactive({ username: 'passenger', password: 'Demo123456!' })
const loading = ref(false),
  router = useRouter()
async function submit() {
  if (!form.username || !form.password) return
  loading.value = true
  try {
    const u = await login(form)
    router.push(home(u.role))
  } catch (e) {
    showError(e)
  } finally {
    loading.value = false
  }
}
const roles = [
  ['passenger', '乘客', '查询到站 · 上报候车'],
  ['driver', '司机', '模拟到站 · 更新人数'],
  ['admin', '管理员', '站点需求 · 车辆运行'],
]
</script>
<template>
  <main class="login-layout">
    <section class="login-intro">
      <div class="eyebrow">校园出行 / 智能调度演示</div>
      <h1>每一次候车，<br />都有据可循。</h1>
      <p>从站点需求到车辆到站，<br />让每一次运力补充都有迹可循。</p>
      <div class="route-illustration" aria-label="模拟线路A站至D站">
        <span v-for="(s, i) in ['A', 'B', 'C', 'D']" :key="s"
          ><b>{{ s }}</b
          ><small>{{ ['起点', '4分钟', '3分钟', '5分钟'][i] }}</small></span
        >
      </div>
      <p class="muted tiny">固定单向线路 · 基于到站上报估算 ETA</p>
    </section>
    <section class="login-form">
      <div class="eyebrow">欢迎使用</div>
      <h2>登录工作台</h2>
      <p class="muted">选择演示身份，体验三端数据联动。</p>
      <div class="role-selector">
        <button
          v-for="r in roles"
          :key="r[0]"
          :class="{ selected: form.username === r[0] }"
          @click="form.username = r[0]"
        >
          <b>{{ r[1] }}</b
          ><small>{{ r[2] }}</small>
        </button>
      </div>
      <el-form label-position="top" @submit.prevent="submit"
        ><el-form-item label="用户名"
          ><el-input v-model="form.username" autocomplete="username" maxlength="60" /></el-form-item
        ><el-form-item label="密码"
          ><el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="current-password"
            maxlength="100" /></el-form-item
        ><el-button
          class="full"
          type="primary"
          size="large"
          native-type="submit"
          :loading="loading"
          :disabled="!form.username || !form.password"
          >登录系统 →</el-button
        ></el-form
      >
      <div class="login-note">
        演示账号：passenger / driver / admin<br />统一密码：Demo123456!<br />备勤接单：driver2（高峰场景可用
        driver4）<br />仅用于本地课程演示
      </div>
    </section>
  </main>
</template>
