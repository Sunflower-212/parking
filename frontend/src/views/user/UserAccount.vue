<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { feedback as ElMessage, localFailure } from '@/utils/request-error-notice'
import { confirmPaymentClose, closePayment } from '@/utils/payment-close'
import DOMPurify from 'dompurify'
import { useRouter, useRoute } from 'vue-router'
import { rechargeId, paymentPath, rememberPayment } from '@/utils/payment-context'
import request from '@/utils/request'
import {
  membershipAttempt,
  readMembershipAttempt,
  clearMembershipAttempt,
  membershipEnd,
  paymentSuccessMessage,
} from '@/utils/membership'
import useUser from '@/stores/userInfo'
import PageShell from '@/components/PageShell.vue'
import StatusTag from '@/components/StatusTag.vue'
import { money, dateTime } from '@/utils/format'
const router = useRouter(),
  route = useRoute()
const paying = ref(false)
const store = useUser(),
  info = computed(() => store.info),
  busy = ref(false),
  loading = ref(false),
  accountError = ref(''),
  amount = ref(100),
  plans = ref([]),
  selectedPlan = ref('MONTHLY'),
  pendingMembership = ref(null),
  membershipError = ref(''),
  membershipLoading = ref(false),
  uncertainAttempt = ref(false),
  capabilities = ref({}),
  payment = ref(null),
  paymentHtml = ref(''),
  dialog = ref(false),
  formContainer = ref()
const availableBalance = computed(() =>
  Number(info.value.available_balance ?? info.value.balance ?? 0),
)
const selected = computed(() => plans.value.find((plan) => plan.code === selectedPlan.value))
const balancePart = computed(() =>
  Math.min(availableBalance.value, Number(selected.value?.price || 0)),
)
const supplement = computed(() =>
  Math.max(0, Math.round((Number(selected.value?.price || 0) - balancePart.value) * 100) / 100),
)
const expectedEnd = computed(() =>
  selected.value ? membershipEnd(info.value.vip_end_time, selected.value.months) : null,
)
const membershipAction = computed(() =>
  uncertainAttempt.value
    ? '核对上次购买'
    : supplement.value > 0
      ? `支付宝补差 ${money(supplement.value)}`
      : `余额支付 ${money(selected.value?.price)}`,
)
const canContinue = computed(
  () =>
    pendingMembership.value?.paymentState === 'PENDING' &&
    pendingMembership.value.expiresAt >
      new Intl.DateTimeFormat('sv-SE', {
        timeZone: 'Asia/Shanghai',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hourCycle: 'h23',
      })
        .format(new Date())
        .replace(' ', 'T'),
)
let membershipController,
  membershipVersion = 0
let disposed = false,
  refreshing = false,
  refreshVersion = 0,
  refreshController
watch(
  () => info.value.id,
  (id) => {
    if (!id) return
    if (pendingMembership.value) {
      clearMembershipAttempt(id)
      selectedPlan.value = pendingMembership.value.planCode
      uncertainAttempt.value = false
      return
    }
    const attempt = readMembershipAttempt(id)
    if (attempt) selectedPlan.value = attempt.planCode
    uncertainAttempt.value = !!attempt
  },
  { immediate: true },
)
async function refresh(silent = false, force = false) {
  if (disposed || (refreshing && !force)) return false
  const version = ++refreshVersion
  refreshController?.abort()
  const controller = new AbortController()
  refreshController = controller
  refreshing = true
  loading.value = true
  try {
    const result = await request.get('/user/userInfo', {
      feedback: !silent,
      signal: controller.signal,
    })
    if (disposed || version !== refreshVersion) return false
    store.setInfo(result.data)
    accountError.value = ''
    return true
  } catch {
    if (!disposed && version === refreshVersion && !controller.signal.aborted)
      accountError.value = '账户刷新失败，请重试'
    return false
  } finally {
    if (!disposed && version === refreshVersion) {
      refreshing = false
      refreshController = undefined
      loading.value = false
    }
  }
}
async function loadMembership(silent = false) {
  const version = ++membershipVersion
  membershipController?.abort()
  const controller = new AbortController()
  membershipController = controller
  membershipLoading.value = true
  try {
    const [options, current] = await Promise.all([
      request.get('/user/membership/plans', { signal: controller.signal, feedback: !silent }),
      request.get('/user/membership/orders/current', {
        signal: controller.signal,
        feedback: !silent,
      }),
    ])
    if (disposed || version !== membershipVersion) return false
    if (!Array.isArray(options.data)) throw new Error('Invalid membership plans')
    plans.value = options.data
    pendingMembership.value = current.data
    if (current.data) {
      selectedPlan.value = current.data.planCode
      clearMembershipAttempt(info.value.id)
      uncertainAttempt.value = false
    }
    membershipError.value = ''
    return true
  } catch {
    if (!disposed && version === membershipVersion && !controller.signal.aborted)
      membershipError.value = '会员信息加载失败，请重试'
    return false
  } finally {
    if (!disposed && version === membershipVersion) membershipLoading.value = false
  }
}
async function refreshAccount() {
  await Promise.all([refresh(), loadMembership()])
}
async function openMembershipPayment(order) {
  const result = await request.post(`/user/membership/orders/${order.id}/pay`)
  if (disposed) return
  payment.value = result.data
  paymentHtml.value = DOMPurify.sanitize(result.data.form)
  paying.value = false
  dialog.value = true
}
async function membership() {
  if (busy.value || !info.value.id || !selected.value || pendingMembership.value) return
  busy.value = true
  membershipVersion++
  membershipController?.abort()
  try {
    const attempt = membershipAttempt(info.value.id, selectedPlan.value)
    uncertainAttempt.value = true
    const result = await request.post(
      '/user/membership/orders',
      { planCode: attempt.planCode },
      { headers: { 'Idempotency-Key': attempt.key } },
    )
    if (disposed) return
    const order = result.data
    clearMembershipAttempt(info.value.id)
    uncertainAttempt.value = false
    if (order.state === 'SUCCEEDED') {
      clearMembershipAttempt(info.value.id)
      const refreshed = await refresh(true, true)
      if (disposed) return
      const text = order.renewed ? '会员已续期' : '会员已开通'
      refreshed ? ElMessage.success(text) : ElMessage.warning(`${text}，账户刷新失败，请重试`)
    } else if (order.state === 'CLOSED') {
      clearMembershipAttempt(info.value.id)
      ElMessage.info('会员订单已关闭，请重新选择套餐')
      await refresh(true, true)
    } else {
      pendingMembership.value = order
      await refresh(true, true)
      if (!disposed) await openMembershipPayment(order)
    }
  } catch (error) {
    if (error.code === 1) {
      clearMembershipAttempt(info.value.id)
      uncertainAttempt.value = false
    }
    localFailure(error, '会员操作失败，请重试')
    if (!disposed) await Promise.all([refresh(true, true), loadMembership(true)])
  } finally {
    busy.value = false
    membershipLoading.value = false
  }
}
async function continueMembership() {
  if (busy.value || !pendingMembership.value) return
  busy.value = true
  try {
    await openMembershipPayment(pendingMembership.value)
  } catch (error) {
    localFailure(error, '支付页面暂不可用，请重试')
  } finally {
    busy.value = false
  }
}
async function cancelMembership() {
  if (busy.value || !pendingMembership.value) return
  await cancel(pendingMembership.value.rechargeId)
}
async function recharge() {
  if (busy.value) return
  if (!Number.isFinite(amount.value) || amount.value <= 0) {
    ElMessage.warning('请输入有效充值金额')
    return
  }
  busy.value = true
  try {
    const result = await request.post(
      '/user/recharge',
      new URLSearchParams({ amounts: amount.value, pay_method: 0 }),
    )
    payment.value = result.data
    paymentHtml.value = DOMPurify.sanitize(result.data.form)
    dialog.value = true
    ElMessage.success('充值单已创建')
  } catch (error) {
    localFailure(error, '创建充值单失败，请重试')
  } finally {
    busy.value = false
  }
}
async function pay() {
  if (paying.value || busy.value) return
  paying.value = true
  try {
    const form = formContainer.value?.querySelector('form')
    if (!form) throw new Error('missing form')
    const address = new URL(form.action)
    if (address.protocol !== 'https:' || !/(^|\.)alipay(dev)?\.com$/.test(address.hostname)) {
      throw new Error('支付地址无效')
    }
    const status = (await request.get(`/user/recharge/${payment.value.rechargeId}/status`)).data
    if (disposed) return
    if (status.paymentState !== 'PENDING' || !status.canClose) {
      paying.value = false
      ElMessage.warning(
        status.paymentState === 'CLOSED'
          ? '充值单已关闭，不能继续支付'
          : '订单状态已变化，请核验到账',
      )
      await refreshAccount()
      return
    }
    rememberPayment(info.value.id, payment.value)
    form.target = '_self'
    paying.value = true
    form.submit()
  } catch (error) {
    paying.value = false
    localFailure(error, '支付页面不可用，请刷新充值单')
  }
}
async function cancel(id = payment.value?.rechargeId) {
  if (busy.value) return
  busy.value = true
  try {
    const order = pendingMembership.value
    const isMembership = order?.rechargeId === id
    if (
      !(await confirmPaymentClose(
        id,
        isMembership ? order.supplementAmount : payment.value?.reAmounts,
        isMembership,
      )) ||
      disposed
    )
      return
    const result = { data: await closePayment(id) }
    if (disposed) return
    dialog.value = false
    if (result.data?.paymentState === 'CLOSED' && result.data?.membershipOrder)
      clearMembershipAttempt(info.value.id)
    ElMessage.info(
      result.data?.paymentState === 'PAID'
        ? paymentSuccessMessage(result.data)
        : result.data?.paymentState === 'CLOSED'
          ? result.data?.membershipOrder
            ? '会员订单已关闭，余额预留已解除'
            : '充值单已关闭'
          : '已提交取消，正在核验支付状态',
    )
  } catch (error) {
    if (!disposed) localFailure(error, '关单结果未确认，请刷新后重试')
  } finally {
    busy.value = false
    if (!disposed) await Promise.all([refresh(true, true), loadMembership(true)])
  }
}
function focus() {
  if (!document.hidden && !busy.value) {
    refresh(true)
    loadMembership(true)
  }
}
onMounted(async () => {
  const returned = rechargeId(route?.query)
  if (returned) {
    router?.replace(paymentPath(returned))
    return
  }
  refresh()
  loadMembership()
  try {
    capabilities.value = (await request.get('/system/capabilities')).data
  } catch {}
  window.addEventListener('focus', focus)
  document.addEventListener('visibilitychange', focus)
})
onBeforeUnmount(() => {
  disposed = true
  membershipVersion++
  membershipController?.abort()
  refreshVersion++
  refreshController?.abort()
  window.removeEventListener('focus', focus)
  document.removeEventListener('visibilitychange', focus)
})
</script>
<template>
  <PageShell title="个人账户"
    ><template #actions
      ><el-button :loading="loading" @click="refreshAccount" :disabled="busy"
        >刷新账户</el-button
      ></template
    >
    <el-alert v-if="accountError" :title="accountError" type="error" :closable="false"
      ><el-button link @click="refreshAccount" :disabled="busy">重试</el-button></el-alert
    >
    <div class="account-grid" v-loading="loading">
      <section class="surface">
        <span class="muted">可用余额</span><strong>{{ money(availableBalance) }}</strong>
        <p v-if="Number(info.reserved_balance) > 0" class="muted">
          待支付预留 {{ money(info.reserved_balance) }}
        </p>

        <el-divider />
        <h3>余额充值</h3>
        <el-alert
          v-if="!capabilities.alipay"
          title="支付宝尚未启用，请联系管理员配置。"
          type="info"
          :closable="false"
        /><el-form label-position="top" @submit.prevent="recharge"
          ><el-form-item label="充值金额 / 元"
            ><el-input-number
              v-model="amount"
              :min="0.01"
              :step="0.01"
              :max="1000000"
              :precision="2" /></el-form-item
          ><el-button
            type="primary"
            native-type="submit"
            :disabled="!capabilities.alipay"
            :loading="busy"
            >支付宝充值</el-button
          ></el-form
        >
      </section>
      <section class="surface">
        <span class="muted">会员状态</span
        ><strong><StatusTag :value="info.status" kind="member" /></strong>
        <p v-if="info.vip_end_time" class="muted">有效期至 {{ dateTime(info.vip_end_time) }}</p>
        <el-divider />
        <h3>{{ info.vip_start_time ? '续期会员' : '开通会员' }}</h3>
        <el-alert v-if="membershipError" :title="membershipError" type="error" :closable="false">
          <el-button link @click="loadMembership()">重试</el-button>
        </el-alert>
        <div v-if="pendingMembership" class="pending-membership" aria-live="polite">
          <div class="membership-summary">
            <strong>{{ pendingMembership.planName }}会员</strong
            ><StatusTag :value="pendingMembership.paymentState" kind="payment" />
          </div>
          <p>
            已预留 {{ money(pendingMembership.reservedAmount) }} · 待补差
            {{ money(pendingMembership.supplementAmount) }}
          </p>
          <p class="muted">付款截止 {{ dateTime(pendingMembership.expiresAt) }}</p>
          <p v-if="Number(pendingMembership.reservedAmount) > 0" class="muted">
            预留金额暂不可用于停车扣费
          </p>
          <div class="membership-actions">
            <el-button v-if="canContinue" type="primary" :loading="busy" @click="continueMembership"
              >继续支付</el-button
            >
            <el-button
              :disabled="busy"
              @click="router.push(paymentPath(pendingMembership.rechargeId))"
              >核验支付</el-button
            >
            <el-button :loading="busy" @click="cancelMembership">取消订单</el-button>
          </div>
        </div>
        <el-form
          v-else
          label-position="top"
          @submit.prevent="membership"
          v-loading="membershipLoading"
        >
          <div class="membership-plans" role="group" aria-label="会员套餐">
            <button
              v-for="plan in plans"
              :key="plan.code"
              type="button"
              class="membership-plan"
              :class="{ selected: selectedPlan === plan.code }"
              :aria-pressed="selectedPlan === plan.code"
              :disabled="busy || uncertainAttempt"
              @click="selectedPlan = plan.code"
            >
              <span>{{ plan.name }}</span
              ><strong>{{ money(plan.price) }}</strong>
            </button>
          </div>
          <div v-if="selected" class="membership-quote">
            <p>预计到期 {{ dateTime(expectedEnd) }}</p>
            <p>
              余额支付 {{ money(balancePart)
              }}<span v-if="supplement > 0"> · 需补差 {{ money(supplement) }}</span>
            </p>
          </div>
          <el-button
            type="primary"
            native-type="submit"
            :loading="busy"
            :disabled="
              !info.id ||
              !selected ||
              !!membershipError ||
              membershipLoading ||
              (!uncertainAttempt && supplement > 0 && !capabilities.alipay)
            "
            >{{ membershipAction }}</el-button
          >
          <p v-if="supplement > 0 && !capabilities.alipay" class="muted">
            余额不足，支付宝尚未启用
          </p>
        </el-form>
      </section>
    </div>
    <el-dialog
      v-model="dialog"
      title="支付宝沙箱支付"
      width="min(480px, calc(100vw - 32px))"
      :close-on-click-modal="false"
      @closed="refreshAccount"
      ><p>{{ payment?.rechargeName || '充值金额' }} {{ money(payment?.reAmounts) }}</p>
      <p class="muted">支付后将自动核验到账。</p>
      <div ref="formContainer" v-html="paymentHtml" style="display: none" />
      <template #footer
        ><el-button @click="cancel()" :loading="busy">取消支付</el-button
        ><el-button type="primary" :loading="paying" :disabled="busy" @click="pay"
          >前往支付宝沙箱</el-button
        ></template
      ></el-dialog
    ></PageShell
  >
</template>
<style scoped>
.surface h3 {
  font-size: 17px;
}
.surface strong .el-tag {
  font-size: 17px;
  height: 36px;
  padding: 0 16px;
}
</style>

<style scoped>
.membership-plans {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}
.membership-plan {
  display: grid;
  gap: 10px;
  padding: 16px 6px;
  border: 1px solid #dce5f1;
  border-radius: 12px;
  background: white;
  color: #526078;
  cursor: pointer;
  font: inherit;
}
.membership-plan.selected {
  background: #edf5ff;
  border-color: #409eff;
  color: #246bc2;
  box-shadow: 0 0 0 1px #409eff;
}
.membership-plan:focus-visible {
  outline: 3px solid #1f64bd;
  outline-offset: 3px;
}
.membership-plan strong {
  font-size: 20px;
  white-space: nowrap;
}
.membership-plan:disabled {
  cursor: wait;
}
.membership-quote {
  margin: 18px 0;
}
.membership-quote p {
  margin: 8px 0;
}
.membership-summary {
  display: flex;
  align-items: center;
  gap: 12px;
}
.membership-summary strong {
  font-size: 18px;
}
.membership-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.membership-actions .el-button {
  margin-left: 0;
}
@media (max-width: 1200px) {
  .account-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 480px) {
  .membership-plan strong {
    font-size: 17px;
  }
  .membership-plans {
    gap: 8px;
  }
}
</style>
