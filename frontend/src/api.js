import axios from 'axios'
import { reactive } from 'vue'
import { ElMessage } from 'element-plus'
export const api = axios.create({ baseURL: '/api', withCredentials: true, timeout: 12000 })
let csrf
export async function prepareCsrf() {
  csrf = await api.get('/auth/csrf')
  return csrf
}
api.interceptors.request.use(async (config) => {
  if (!['get', 'head', 'options'].includes(config.method)) {
    if (!csrf) await prepareCsrf()
    config.headers[csrf.headerName] = csrf.token
  }
  return config
})
api.interceptors.response.use(
  (response) => response.data.data,
  (error) => {
    if (error.response?.status === 401 && !error.config?.url?.includes('/auth/')) {
      session.user = null
      if (location.pathname !== '/login') location.assign('/login')
    }
    return Promise.reject(error)
  },
)
// The public CSRF request also passes through the response interceptor.
export async function csrfReady() {
  csrf = await api.get('/auth/csrf')
}
export const session = reactive({ user: null, ready: false })
export const home = (role) =>
  ({ PASSENGER: '/passenger', DRIVER: '/driver', ADMIN: '/admin/overview' })[role] || '/login'
export async function restore() {
  try {
    session.user = await api.get('/auth/me')
  } catch {
    session.user = null
  } finally {
    session.ready = true
  }
}
export async function login(body) {
  await csrfReady()
  session.user = await api.post('/auth/login', body)
  await csrfReady()
  return session.user
}
export async function logout() {
  await api.post('/auth/logout')
  session.user = null
  csrf = null
}
export const messageOf = (e) =>
  e.response?.data?.message ||
  (e.code === 'ECONNABORTED' ? '请求超时，请重试' : '无法连接服务，请检查后端是否启动')
export function showError(e) {
  ElMessage.error(messageOf(e))
}
export const time = (value) =>
  value
    ? new Intl.DateTimeFormat('zh-CN', {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false,
      }).format(new Date(value))
    : '—'
export const states = {
  WAITING: '候车中',
  BOARDED: '已上车',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
  RUNNING: '运行中',
  STANDBY: '待命',
  STOPPED: '已停运',
  UNAVAILABLE: '不可用',
}

export const reportStale = (value) =>
  !value || Date.now() - new Date(value).getTime() >= 15 * 60 * 1000
