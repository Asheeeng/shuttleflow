import { ref, onMounted, onUnmounted } from 'vue'
import { showError, messageOf } from './api'
export function usePoll(load) {
  const loading = ref(false),
    error = ref(''),
    updated = ref(null)
  let timer,
    alive = true
  async function refresh(quiet = false) {
    if (loading.value || !alive) return
    loading.value = true
    try {
      await load()
      error.value = ''
      updated.value = new Date().toISOString()
    } catch (e) {
      error.value = messageOf(e)
      if (!quiet) showError(e)
    } finally {
      loading.value = false
    }
  }
  onMounted(() => {
    refresh()
    timer = setInterval(() => refresh(true), 10000)
  })
  onUnmounted(() => {
    alive = false
    clearInterval(timer)
  })
  return { loading, error, updated, refresh }
}
