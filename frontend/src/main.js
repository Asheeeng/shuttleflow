import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import Login from './views/Login.vue'
import Passenger from './views/Passenger.vue'
import Driver from './views/Driver.vue'
import Admin from './views/Admin.vue'
import { session, restore, home } from './api'
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: () => home(session.user?.role) },
    { path: '/login', component: Login },
    { path: '/passenger', component: Passenger, meta: { role: 'PASSENGER' } },
    { path: '/driver', component: Driver, meta: { role: 'DRIVER' } },
    { path: '/admin/:tab?', component: Admin, meta: { role: 'ADMIN' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
router.beforeEach(async (to) => {
  if (!session.ready) await restore()
  if (to.path === '/login') return session.user ? home(session.user.role) : true
  if (!session.user) return '/login'
  if (to.meta.role && to.meta.role !== session.user.role) return home(session.user.role)
  return true
})
createApp(App).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
