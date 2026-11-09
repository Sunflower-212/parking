<script setup>
import { ref, onMounted } from 'vue'
import request from '@/utils/request'
import PageShell from '@/components/PageShell.vue'
import GateLane from '@/components/GateLane.vue'
const parks = ref([]),
  parkId = ref(),
  ocr = ref(false),
  loading = ref(true),
  error = ref('')
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [lots, features] = await Promise.all([
      request.get('/parking/catalog'),
      request.get('/system/capabilities'),
    ])
    parks.value = lots.data
    parkId.value = parks.value[0]?.id
    ocr.value = features.data.ocr
  } catch {
    error.value = '加载失败，请重试'
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>
<template>
  <PageShell title="出入口管理"
    ><template #actions
      ><el-select
        v-model="parkId"
        placeholder="选择停车场"
        aria-label="出入口停车场"
        style="width: 220px"
        ><el-option
          v-for="p in parks"
          :key="p.id"
          :label="p.park_name"
          :value="p.id" /></el-select></template
    ><el-alert v-if="error" :title="error" type="error" :closable="false"
      ><el-button link @click="load">重试</el-button></el-alert
    ><el-alert
      v-if="!ocr && !loading"
      title="车牌识别尚未启用，请联系管理员配置百度 OCR；当前不会发起识别或出入场请求。"
      type="info"
      :closable="false" />
    <div class="account-grid" v-loading="loading">
      <GateLane mode="enter" :key="'enter-' + parkId" :park-id="parkId" :enabled="ocr" /><GateLane
        mode="exit"
        :key="'exit-' + parkId"
        :park-id="parkId"
        :enabled="ocr"
      /></div
  ></PageShell>
</template>
