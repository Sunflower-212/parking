<template>
  <el-popover trigger="click" title="帮助" :width="280">
    <template #reference><el-button text aria-label="查看帮助">帮助</el-button></template>
    <div class="page-help"><slot /></div>
  </el-popover>
</template>
<style scoped>
.page-help {
  font-size: 13px;
  line-height: 1.8;
}
.page-help :deep(p:last-child) {
  margin-bottom: 0;
}
</style>
