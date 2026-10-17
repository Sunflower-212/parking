<template>
  <el-form class="search-bar" inline @submit.prevent="$emit('search')"
    ><slot /><el-form-item
      ><el-button type="primary" native-type="submit">搜索</el-button
      ><el-button @click="$emit('reset')">重置</el-button></el-form-item
    ></el-form
  >
</template>
