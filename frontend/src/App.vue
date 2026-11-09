<script setup>
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import FailureAlerts from '@/components/FailureAlerts.vue'
</script>
<template>
  <el-config-provider :locale="zhCn"><router-view /><FailureAlerts /></el-config-provider>
</template>
