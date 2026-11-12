<script setup>
import Icon from './components/Icon.vue'
import { useRouter } from 'vue-router'
import { session, logout, showError } from './api'
const router = useRouter()
async function exit() {
  try {
    await logout()
    router.push('/login')
  } catch (e) {
    showError(e)
  }
}
</script>
<template>
  <header class="topbar">
    <RouterLink class="brand" to="/"
      ><span class="brand-mark"><Icon name="bus" :size="24" /></span
      ><span>校园摆渡<small>CAMPUS LINK / V0.2</small></span></RouterLink
    >
    <div class="header-right">
      <span class="demo-badge"><i class="live-dot"></i> 演示环境</span
      ><template v-if="session.user"
        ><span class="user-name">{{ session.user.displayName }}</span
        ><el-button text @click="exit">退出登录</el-button></template
      >
    </div>
  </header>
  <RouterView />
  <footer class="footer">
    规则辅助调度 V0.2 · 所有线路、车辆、站间耗时均为模拟数据，不代表贵州大学真实运营信息。
  </footer>
</template>
