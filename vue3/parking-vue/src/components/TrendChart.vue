<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { init, use } from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])
const props = defineProps({
    data: { type: Array, default: () => [] },
    series: { type: Array, default: () => [] },
  }),
  element = ref()
let chart, observer
function render() {
  chart?.setOption(
    {
      color: ['#2563eb', '#0d9488', '#f59e0b'],
      tooltip: { trigger: 'axis' },
      legend: { top: 0, icon: 'roundRect' },
      grid: { left: 48, right: 24, top: 42, bottom: 30 },
      xAxis: {
        type: 'category',
        data: props.data.map((d) => d.date.slice(5)),
        axisLine: { lineStyle: { color: '#dce3ed' } },
        axisLabel: { color: '#8491a5' },
      },
      yAxis: {
        type: 'value',
        splitLine: { lineStyle: { color: '#eef2f7' } },
        axisLabel: { color: '#8491a5' },
      },
      series: props.series.map((s) => ({
        name: s.label,
        type: s.type || 'line',
        smooth: true,
        symbolSize: 5,
        lineStyle: { width: 3 },
        data: props.data.map((d) => d[s.key] || 0),
      })),
    },
    true,
  )
}
onMounted(() => {
  chart = init(element.value)
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(element.value)
  render()
})
watch(() => [props.data, props.series], render, { deep: true })
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
  chart = null
})
</script>
<template>
  <div ref="element" class="chart" role="img" aria-label="统计趋势图，下方可切换查看数据表" />
</template>
