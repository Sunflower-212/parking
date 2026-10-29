<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { feedback as ElMessage, localFailure } from '@/utils/request-error-notice'
import DOMPurify from 'dompurify'
import request from '@/utils/request'
import useUser from '@/stores/userInfo'
import PageShell from '@/components/PageShell.vue'
import StatusTag from '@/components/StatusTag.vue'
import { money, dateTime } from '@/utils/format'
const store = useUser(),
  info = computed(() => store.info),
  busy = ref(false),
  loading = ref(false),
  accountError = ref(''),
  amount = ref(100),
  days = ref(30),
  capabilities = ref({}),
  payment = ref(null),
  paymentHtml = ref(''),
  dialog = ref(false),
  formContainer = ref()
let disposed = false,
  refreshing = false
async function refresh(silent = false) {
  if (refreshing) return
  refreshing = true
  loading.value = true
  try {
    const result = await request.get('/user/userInfo', { feedback: !silent })
    if (!disposed) {
      store.setInfo(result.data)
      accountError.value = ''
    }
    return true
  } catch {
    if (!disposed) accountError.value = '账户刷新失败，请重试'
    return false
  } finally {
    refreshing = false
    loading.value = false
  }
}
async function membership() {
  if (busy.value) return
  busy.value = true
  try {
    const renew = info.value.vip_start_time
    await request.patch(
      renew ? '/user/vipRenewal' : '/user/getVip',
      new URLSearchParams({ days: days.value }),
    )
    // 会员变更成功后重新获取服务端账户信息，避免余额或会员标签继续显示旧状态。
    const refreshed = await refresh(true)
    refreshed
      ? ElMessage.success('会员信息已更新')
      : ElMessage.warning('会员信息已更新，账户刷新失败，请重试')
  } catch (error) {
    localFailure(error, '会员操作失败，请重试')
  } finally {
    busy.value = false
  }
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
function pay() {
  try {
    const form = formContainer.value?.querySelector('form')
    if (!form) throw new Error('missing form')
    const address = new URL(form.action)
    if (address.protocol !== 'https:' || !/(^|\.)alipay(dev)?\.com$/.test(address.hostname)) {
      ElMessage.error('支付地址无效')
      return
    }
    form.target = '_blank'
    form.rel = 'noopener'
    form.submit()
  } catch (error) {
    localFailure(error, '支付页面不可用，请刷新充值单')
  }
}
async function cancel() {
  if (busy.value) return
  busy.value = true
  try {
    await request.post(
      '/user/cancelRecharges',
      new URLSearchParams({ rechargesId: payment.value.rechargeId }),
    )
    dialog.value = false
    ElMessage.info('支付已取消')
  } catch {
  } finally {
    busy.value = false
    refresh(true)
  }
}
function focus() {
  if (!document.hidden) refresh(true)
}
onMounted(async () => {
  refresh()
  try {
    capabilities.value = (await request.get('/system/capabilities')).data
  } catch {}
  window.addEventListener('focus', focus)
  document.addEventListener('visibilitychange', focus)
})
onBeforeUnmount(() => {
  disposed = true
  window.removeEventListener('focus', focus)
  document.removeEventListener('visibilitychange', focus)
})
</script>
<template>
  <PageShell title="个人账户"
    ><template #actions
      ><el-button :loading="loading" @click="refresh(false)">刷新账户</el-button></template
    >
    <el-alert v-if="accountError" :title="accountError" type="error" :closable="false"
      ><el-button link @click="refresh(false)">重试</el-button></el-alert
    >
    <div class="account-grid" v-loading="loading">
      <section class="surface">
        <span class="muted">账户余额</span><strong>{{ money(info.balance) }}</strong>

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
        <p class="muted">
          {{ info.vip_end_time ? '有效期至 ' + dateTime(info.vip_end_time) : '尚未开通会员' }}
        </p>
        <el-divider />
        <h3>{{ info.vip_start_time ? '续期会员' : '开通会员' }}</h3>
        <el-form label-position="top" @submit.prevent="membership"
          ><el-form-item label="会员天数"
            ><el-input-number v-model="days" :min="1" :max="3650" :precision="0" /></el-form-item
          ><el-button type="primary" native-type="submit" :loading="busy">{{
            info.vip_start_time ? '确认续期' : '确认开通'
          }}</el-button></el-form
        >
      </section>
    </div>
    <el-dialog
      v-model="dialog"
      title="支付宝沙箱支付"
      width="480px"
      :close-on-click-modal="false"
      @closed="refresh"
      ><p>充值金额：{{ money(payment?.reAmounts) }}</p>
      <p class="muted">支付完成后请刷新账户，最终支付状态以回调确认为准。</p>
      <div ref="formContainer" v-html="paymentHtml" style="display: none" />
      <template #footer
        ><el-button @click="cancel" :loading="busy">取消支付</el-button
        ><el-button type="primary" @click="pay">前往支付宝沙箱</el-button></template
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
