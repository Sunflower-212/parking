<script setup>
import { computed } from 'vue'
const props = defineProps({
  value: [String, Number],
  reservation: [String, Number],
  kind: { type: String, default: 'entry' },
})
const baseState = computed(
  () =>
    ({
      entry: { 0: ['待入场', 'warning'], 1: ['在场', 'success'], 2: ['已离场', 'info'] },
      payment: { 0: ['未支付', 'warning'], 1: ['支付成功', 'success'], 2: ['已取消', 'info'] },
      member: { 0: ['普通用户', 'info'], 1: ['会员', 'success'] },
      role: { 0: ['停车场管理员', 'info'], 1: ['超级管理员', 'primary'] },
      space: {
        0: ['空闲', 'success'],
        1: ['占用', 'danger'],
        2: ['道路', 'info'],
        3: ['墙体', 'info'],
        4: ['出入口', 'warning'],
      },
    })[props.kind]?.[props.value] || ['未知', 'info'],
)
const state = computed(() => {
  if (props.kind === 'entry' && Number(props.value) === 0 && props.reservation !== undefined) {
    return Number(props.reservation) === 1
      ? ['预约中', 'warning']
      : Number(props.reservation) === 2
        ? ['取消或过期', 'info']
        : ['未入场', 'info']
  }
  return baseState.value
})
</script>
<template>
  <el-tag :type="state[1]" effect="light">{{ state[0] }}</el-tag>
</template>
