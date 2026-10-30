<script setup>
import { computed, ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '@/utils/request'
import useUser from '@/stores/userInfo'
import { rechargeId, pendingPayment, clearPayment } from '@/utils/payment-context'
import { feedback } from '@/utils/request-error-notice'
import PageShell from '@/components/PageShell.vue'
import { money } from '@/utils/format'

const route = useRoute(),
  router = useRouter(),
  user = useUser()
const id = computed(() => rechargeId(route.query) || pendingPayment(user.info.id))
const status = ref(null),
  checking = ref(false),
  error = ref(''),
  waiting = ref(false)
let controller,
  timer,
  deadline = 0,
  disposed = false,
  version = 0
const title = computed(() =>
  status.value?.paymentState === 'PAID'
    ? '充值已到账'
    : status.value?.paymentState === 'CLOSED'
      ? '充值单已关闭'
      : waiting.value
        ? '到账尚未确认'
        : '到账确认中',
)
function stop() {
  clearTimeout(timer)
  timer = undefined
}
function schedule() {
  stop()
  if (disposed || document.hidden || ['PAID', 'CLOSED'].includes(status.value?.paymentState)) return
  if (Date.now() >= deadline) {
    waiting.value = true
    return
  }
  timer = setTimeout(() => load(false), 3000)
}
async function finish(own) {
  stop()
  try {
    const result = await request.get('/user/userInfo', { feedback: false, signal: own.signal })
    if (disposed || controller !== own) return
    user.setInfo(result.data)
    clearPayment(user.info.id, id.value)
    feedback.success('充值成功，余额已更新')
    await router.replace('/user/account')
  } catch {
    if (!disposed && controller === own && !own.signal.aborted)
      error.value = '充值已到账，账户刷新失败，请重试'
  }
}
async function load(active = false) {
  if (disposed || checking.value || !id.value) return
  const ownVersion = ++version
  controller?.abort()
  const own = new AbortController()
  controller = own
  checking.value = true
  error.value = ''
  try {
    if (status.value?.paymentState === 'PAID') {
      await finish(own)
      return
    }
    const path = `/user/recharge/${id.value}`
    const response = active
      ? await request.post(`${path}/check`, null, { signal: own.signal })
      : await request.get(`${path}/status`, { signal: own.signal, feedback: false })
    if (disposed || ownVersion !== version) return
    status.value = response.data
    if (status.value.paymentState === 'PAID') await finish(own)
  } catch (e) {
    if (!disposed && ownVersion === version && e.code !== 'ERR_CANCELED')
      error.value = '核验暂未完成，请稍后重试'
  } finally {
    if (ownVersion === version && !disposed) {
      checking.value = false
      schedule()
    }
  }
}
function retry() {
  waiting.value = false
  deadline = Date.now() + 60000
  return load(true)
}
function start() {
  stop()
  version++
  controller?.abort()
  checking.value = false
  status.value = null
  error.value = ''
  waiting.value = false
  deadline = Date.now() + 60000
  if (id.value) load(true)
}
function visible() {
  if (document.hidden) stop()
  else if (Date.now() >= deadline) waiting.value = true
  else load(false)
}
watch(id, start)
onMounted(() => {
  start()
  document.addEventListener('visibilitychange', visible)
})
onBeforeUnmount(() => {
  disposed = true
  version++
  stop()
  controller?.abort()
  document.removeEventListener('visibilitychange', visible)
})
</script>
<template>
  <PageShell title="充值结果">
    <section class="surface payment-result" aria-live="polite">
      <el-result
        :icon="
          !id || error
            ? 'warning'
            : status?.paymentState === 'PAID'
              ? 'success'
              : status?.paymentState === 'CLOSED'
                ? 'info'
                : 'warning'
        "
        :title="id ? title : '未找到待核验充值单'"
      >
        <template #sub-title>
          <p v-if="id">
            充值单 {{ id }} <span v-if="status"> · {{ money(status.amounts) }}</span>
          </p>
          <p v-if="error" role="alert">{{ error }}</p>
          <p v-else-if="id && status?.paymentState !== 'PAID' && status?.paymentState !== 'CLOSED'">
            以到账核验结果为准
          </p>
        </template>
        <template #extra>
          <el-button
            v-if="id && status?.paymentState !== 'CLOSED'"
            type="primary"
            :loading="checking"
            @click="retry"
          >
            {{ status?.paymentState === 'PAID' ? '刷新账户' : '重新核验' }}
          </el-button>
          <el-button @click="router.replace('/user/account')">返回账户</el-button>
        </template>
      </el-result>
    </section>
  </PageShell>
</template>
<style scoped>
.payment-result {
  max-width: 680px;
  margin: 0 auto;
}
.payment-result p {
  margin: 8px 0;
}
</style>
