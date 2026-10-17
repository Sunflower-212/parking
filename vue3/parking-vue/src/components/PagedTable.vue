<script setup>
defineProps({
  rows: { type: Array, default: () => [] },
  total: Number,
  loading: Boolean,
  error: String,
  pagination: Object,
})
defineEmits(['reload', 'page', 'size'])
</script>
<template>
  <div class="data-panel" :aria-busy="loading">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false"
      ><el-button link type="primary" @click="$emit('reload')">重新加载</el-button></el-alert
    >
    <div class="table-scroll">
      <el-table
        :data="rows"
        v-loading="loading"
        stripe
        row-key="id"
        style="width: 100%"
        empty-text="暂无符合条件的记录"
        ><slot
      /></el-table>
    </div>
    <div class="table-footer">
      <span class="muted">共 {{ total || 0 }} 条记录</span
      ><el-pagination
        background
        :current-page="pagination.pageNum"
        :page-size="pagination.pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="total || 0"
        layout="prev,pager,next,sizes"
        @current-change="$emit('page', $event)"
        @size-change="$emit('size', $event)"
      />
    </div>
  </div>
</template>
