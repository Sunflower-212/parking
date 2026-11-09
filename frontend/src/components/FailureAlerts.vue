<script setup>
import { failureAlerts, closeFailure, feedback } from '@/utils/request-error-notice'
async function copy(requestId) {
  try {
    await navigator.clipboard.writeText(requestId)
    feedback.success('请求 ID 已复制')
  } catch {
    feedback.warning('无法自动复制，请手动复制请求 ID')
  }
}
</script>
<template>
  <Teleport to="body">
    <div v-if="failureAlerts.length" class="failure-alerts">
      <el-alert
        v-for="notice in failureAlerts"
        :key="notice.id"
        data-testid="operation-error-alert"
        type="error"
        effect="light"
        show-icon
        closable
        :title="notice.message"
        @close="closeFailure(notice.id)"
      >
        <details v-if="notice.requestId">
          <summary>查看详情</summary>
          <div class="feedback-details">
            <span>请求 ID：{{ notice.requestId }}</span>
            <button type="button" class="feedback-copy" @click="copy(notice.requestId)">
              复制请求 ID
            </button>
          </div>
        </details>
      </el-alert>
    </div>
  </Teleport>
</template>
<style scoped>
.failure-alerts {
  position: fixed;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 10010;
  width: 360px;
  max-width: calc(100vw - 32px);
  display: grid;
  gap: 8px;
}
.failure-alerts .el-alert {
  margin: 0;
  align-items: flex-start;
}
.failure-alerts :deep(.el-alert__content) {
  min-width: 0;
  flex: 1;
  padding-right: 16px;
  overflow-wrap: anywhere;
}
.failure-alerts :deep(.el-alert__title) {
  font-size: 14px;
  line-height: 1.5;
}
.failure-alerts :deep(.el-alert__icon) {
  margin-top: 2px;
}
details {
  margin-top: 4px;
}
summary {
  cursor: pointer;
  width: fit-content;
}
</style>
