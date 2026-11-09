<script setup>
import { ref, computed, onMounted, onBeforeUnmount, defineAsyncComponent } from 'vue'
import request from '@/utils/request'
import PageShell from '@/components/PageShell.vue'
import { money, dateTime } from '@/utils/format'
// 趋势图按需加载，只有进入统计页时才下载 ECharts 相关代码。
const TrendChart = defineAsyncComponent(() => import('@/components/TrendChart.vue'))
const data = ref(null),
  parks = ref([]),
  parkId = ref(null),
  days = ref(7),
  loading = ref(false),
  error = ref(''),
  table = ref(false)
let controller,
  version = 0,
  disposed = false
const cards = computed(() => {
  const c = data.value?.counters || {}
  return [
    ['可用车位', c.availableSpaces, '当前空闲容量'],
    ['今日入场', c.enteredToday, '不含尚未入场的预约'],
    ['今日离场', c.exitedToday, '已完成离场的停车订单'],
    ['今日停车收入', money(c.revenueToday), '按订单创建时间统计'],
    ...(c.userNumber !== undefined
      ? [
          ['平台用户', c.userNumber, '今日新增 ' + c.todayNewUserNumber],
          ['管理员', c.adminNumber, '平台运营账号'],
        ]
      : []),
  ]
})
async function load() {
  controller?.abort()
  controller = new AbortController()
  const id = ++version
  loading.value = true
  error.value = ''
  try {
    const result = await request.get('/dashboard/summary', {
      params: { park_id: parkId.value || undefined, days: days.value },
      signal: controller.signal,
    })
    if (!disposed && id === version) data.value = result.data
  } catch (e) {
    if (e.code !== 'ERR_CANCELED') error.value = '统计加载失败，请重试'
  } finally {
    if (id === version) loading.value = false
  }
}
onMounted(async () => {
  load()
  try {
    parks.value = (await request.get('/parking/catalog')).data
  } catch {}
})
onBeforeUnmount(() => {
  disposed = true
  version++
  controller?.abort()
})
</script>
<template>
  <PageShell title="运营概览"
    ><template #actions><el-button :loading="loading" @click="load">刷新统计</el-button></template>
    <div class="surface dashboard-filters">
      <el-select
        v-model="parkId"
        clearable
        placeholder="全部可管理停车场"
        aria-label="统计停车场"
        @change="load"
        ><el-option v-for="p in parks" :key="p.id" :label="p.park_name" :value="p.id" /></el-select
      ><el-select v-model="days" aria-label="统计天数" @change="load"
        ><el-option
          v-for="d in [7, 14, 31]"
          :key="d"
          :label="'最近 ' + d + ' 天'"
          :value="d" /></el-select
      ><span class="muted">数据时间：{{ dateTime(data?.updatedAt) }}</span>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false"
      ><el-button link @click="load">重试</el-button></el-alert
    >
    <div v-loading="loading">
      <div class="stat-grid">
        <article class="stat-card" v-for="card in cards" :key="card[0]">
          <el-popover trigger="click" :content="card[2]" :width="240"
            ><template #reference
              ><el-button text class="stat-label">{{ card[0] }}</el-button></template
            ></el-popover
          >
          <strong>{{ card[1] ?? '—' }}</strong>
        </article>
      </div>
      <div class="chart-grid" v-if="data">
        <section class="surface">
          <h3>出入场趋势</h3>
          <TrendChart
            :data="data.series"
            :series="[
              { key: 'entered', label: '入场' },
              { key: 'exited', label: '离场' },
            ]"
          />
        </section>
        <section class="surface">
          <h3>停车收入趋势</h3>
          <TrendChart :data="data.series" :series="[{ key: 'revenue', label: '停车收入 / 元' }]" />
        </section>
        <section class="surface" v-if="data.counters.userNumber !== undefined">
          <h3>平台成功充值</h3>
          <TrendChart
            :data="data.series"
            :series="[{ key: 'rechargeAmount', label: '充值金额 / 元', type: 'bar' }]"
          />
        </section>
      </div>
    </div>
    <section v-if="data" class="surface dashboard-table">
      <el-button link type="primary" @click="table = !table"
        >{{ table ? '收起' : '查看' }}趋势数据表</el-button
      >
      <div v-if="table" class="table-scroll">
        <el-table :data="data.series"
          ><el-table-column prop="date" label="日期" min-width="130" /><el-table-column
            prop="entered"
            label="入场" /><el-table-column prop="exited" label="离场" /><el-table-column
            prop="revenue"
            label="停车收入 / 元"
            min-width="140"
        /></el-table>
      </div></section
  ></PageShell>
</template>
<style scoped>
.dashboard-filters {
  display: flex;
  gap: 16px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 24px;
  padding: 18px;
}
.dashboard-filters .el-select {
  width: 220px;
}
.dashboard-filters .muted {
  font-size: 12px;
}
.surface h3 {
  font-size: 16px;
  color: #344561;
}
.dashboard-table {
  margin-top: 20px;
}
.chart-grid .muted {
  font-size: 12px;
  line-height: 1.7;
}
@media (max-width: 767px) {
  .dashboard-filters .el-select {
    width: 100%;
  }
}
</style>
