<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { jwtDecode } from 'jwt-decode'
import { ElMessageBox } from 'element-plus'
import { feedback as ElMessage } from '@/utils/request-error-notice'
import { Menu, Fold, Expand, SwitchButton, ArrowRight } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { useTokenStore } from '@/stores/token'
import useAdmin from '@/stores/adminInfo'
import useUser from '@/stores/userInfo'
import { roleName } from '@/utils/format'
const props = defineProps({ admin: Boolean })
const route = useRoute(),
  router = useRouter(),
  token = useTokenStore(),
  adminStore = useAdmin(),
  userStore = useUser()
const collapsed = ref(false),
  mobileOpen = ref(false),
  loading = ref(true),
  failed = ref(false)
const role = computed(() => {
  try {
    return jwtDecode(token.token).claims.permissions
  } catch {
    return -1
  }
})
const info = computed(() => (props.admin ? adminStore.info : userStore.info))
const menu = computed(() =>
  props.admin
    ? [
        ['charts', '运营概览', '◫'],
        ['entAndExitManagement', '出入口管理', '↔'],
        ['parkManagement', '停车场管理', 'P'],
        ['spaceManagement', '车位管理', '▦'],
        ['orderManagement', '订单管理', '▤'],
        ['ruleManagement', '计费规则', '¥'],
        ['security', '修改密码', '◇'],
        ...(role.value === 1
          ? [
              ['userManagement', '用户管理', '◎'],
              ['rechargesManagement', '充值管理', '▣'],
              ['adminManagement', '管理员管理', '◇'],
              ['auditLogs', '操作日志', '▤'],
            ]
          : []),
      ]
    : [
        ['spaceReservation', '车位预约', 'P'],
        ['enterHistory', '入场历史', '↔'],
        ['orderHistory', '订单记录', '▤'],
        ['rechargeHistory', '充值记录', '▣'],
        ['account', '个人账户', '¥'],
        ['info', '基本资料', '◎'],
        ['avatar', '更换头像', '◉'],
        ['resetPassword', '修改密码', '◇'],
      ],
)
const prefix = computed(() => (props.admin ? '/admin/' : '/user/'))
const title = computed(
  () => menu.value.find((m) => route.path === prefix.value + m[0])?.[1] || '停车管理',
)
async function load() {
  loading.value = true
  failed.value = false
  try {
    const result = await request.get(props.admin ? '/admin/adminInfo' : '/user/userInfo')
    ;(props.admin ? adminStore : userStore).setInfo(result.data)
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}
async function logout() {
  try {
    await ElMessageBox.confirm('确认退出当前账号？', '退出登录', {
      type: 'warning',
      confirmButtonText: '退出',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    // 服务端退出成功后才清空本地会话；网络或服务错误时保留登录状态供用户重试。
    await request.get(props.admin ? '/admin/logout' : '/user/logout')
    token.removeToken()
    adminStore.removeInfo()
    userStore.removeInfo()
    router.replace('/login')
    ElMessage.success('已退出登录')
  } catch {
    /* request interceptor explains failure */
  }
}
onMounted(load)
</script>
<template>
  <div class="app-layout" :class="{ collapsed }">
    <div v-if="mobileOpen" class="sidebar-mask" @click="mobileOpen = false" />
    <aside class="sidebar" :class="{ open: mobileOpen }">
      <router-link class="brand" :to="prefix"
        ><span class="brand-icon">P</span><span v-if="!collapsed">易停车</span></router-link
      >

      <nav aria-label="主导航">
        <router-link
          v-for="item in menu"
          :key="item[0]"
          :to="prefix + item[0]"
          :title="item[1]"
          @click="mobileOpen = false"
          ><span class="nav-icon">{{ item[2] }}</span
          ><span v-if="!collapsed">{{ item[1] }}</span></router-link
        >
      </nav>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <div class="topbar-left">
          <el-button
            class="desktop-toggle"
            text
            :icon="collapsed ? Expand : Fold"
            aria-label="折叠菜单"
            @click="collapsed = !collapsed"
          /><el-button
            class="mobile-toggle"
            text
            :icon="Menu"
            aria-label="打开菜单"
            @click="mobileOpen = true"
          /><span class="breadcrumb"
            >{{ admin ? '管理工作台' : '用户中心' }} <el-icon><ArrowRight /></el-icon>
            <strong>{{ title }}</strong></span
          >
        </div>
        <div class="profile">
          <el-tag size="small">{{ roleName(role) }}</el-tag
          ><span>{{ info.admin_name || info.user_name || '加载中' }}</span
          ><el-button text :icon="SwitchButton" aria-label="退出登录" @click="logout" />
        </div>
      </header>
      <main class="main-content">
        <el-skeleton v-if="loading" :rows="8" animated /><el-result
          v-else-if="failed"
          icon="warning"
          title="暂时无法加载账号信息"
          ><template #extra><el-button @click="load">重新加载</el-button></template></el-result
        ><router-view v-else />
      </main>
    </div>
  </div>
</template>
