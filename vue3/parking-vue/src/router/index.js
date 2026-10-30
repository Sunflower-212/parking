import { createRouter, createWebHistory } from 'vue-router'

//导入组件
const LoginVue = () => import('@/views/Login.vue')
const UserLayoutVue = () => import('@/views/UserLayout.vue')
const AdminLayoutVue = () => import('@/views/AdminLayout.vue')

const SpaceReservationVue = () => import('@/views/user/SpaceReservation.vue')
const RechargeHistoryVue = () => import('@/views/user/RechargesHistory.vue')
const OrderHistoryVue = () => import('@/views/user/OrderHistory.vue')
const EnterHistoryVue = () => import('@/views/user/EnterHistory.vue')
const UserAccountVue = () => import('@/views/user/UserAccount.vue')
const UserAvatarVue = () => import('@/views/user/UserAvatar.vue')
const UserInfoVue = () => import('@/views/user/UserInfo.vue')
const UserResetPasswordVue = () => import('@/views/user/UserResetPassword.vue')

const EntAndExitManagementVue = () => import('@/views/admin/EntAndExitManagement.vue')
const OrderManagementVue = () => import('@/views/admin/OrderManagement.vue')
const ParkManagementVue = () => import('@/views/admin/ParkManagement.vue')
const RechargesManagementVue = () => import('@/views/admin/RechargesManagement.vue')
const RuleManagementVue = () => import('@/views/admin/RuleManagement.vue')
const SpaceManagementVue = () => import('@/views/admin/SpaceManagement.vue')
const UserManagementVue = () => import('@/views/admin/UserManagement.vue')
const AdminManagementVue = () => import('@/views/admin/AdminManagement.vue')
const ChartsVue = () => import('@/views/admin/Charts.vue')

import { useTokenStore } from '@/stores/token'
import { jwtDecode } from 'jwt-decode'
import { rememberPaymentReturn } from '@/utils/payment-context'

//定义路由关系
const routes = [
  {
    path: '/',
    redirect: '/login', // 将根路径重定向到登录页面
  },
  { path: '/login', component: LoginVue },
  {
    path: '/user',
    component: UserLayoutVue,
    redirect: '/user/spaceReservation',
    //子路由
    children: [
      { path: '/user/spaceReservation', component: SpaceReservationVue },
      { path: '/user/enterHistory', component: EnterHistoryVue },
      { path: '/user/orderHistory', component: OrderHistoryVue },
      { path: '/user/rechargeHistory', component: RechargeHistoryVue },
      { path: '/user/info', component: UserInfoVue },
      { path: '/user/account', component: UserAccountVue },
      {
        path: '/user/paymentResult',
        component: () => import('@/views/user/PaymentResult.vue'),
        meta: { title: '充值结果' },
      },
      { path: '/user/avatar', component: UserAvatarVue },
      { path: '/user/resetPassword', component: UserResetPasswordVue },
    ],
  },
  {
    path: '/admin',
    component: AdminLayoutVue,
    redirect: '/admin/charts',
    //子路由
    children: [
      { path: '/admin/entAndExitManagement', component: EntAndExitManagementVue },
      { path: '/admin/parkManagement', component: ParkManagementVue },
      { path: '/admin/ruleManagement', component: RuleManagementVue },
      { path: '/admin/spaceManagement', component: SpaceManagementVue },
      { path: '/admin/orderManagement', component: OrderManagementVue },
      { path: '/admin/rechargesManagement', component: RechargesManagementVue },
      { path: '/admin/userManagement', component: UserManagementVue },
      { path: '/admin/adminManagement', component: AdminManagementVue },
      { path: '/admin/charts', component: ChartsVue },
      { path: '/admin/security', component: UserResetPasswordVue },
      { path: '/admin/auditLogs', component: () => import('@/views/admin/AuditLog.vue') },
    ],
  },
]

//创建路由器
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: routes,
})

router.beforeEach((to) => {
  if (to.path === '/login') return true
  const tokenStore = useTokenStore()
  try {
    const decoded = jwtDecode(tokenStore.token)
    if (!decoded.exp || decoded.exp * 1000 <= Date.now()) throw new Error('expired')
    const role = decoded.claims?.permissions
    if (![-1, 0, 1].includes(role)) throw new Error('role')
    if (to.path.startsWith('/admin') && role === -1) return '/user'
    if (
      // 路由守卫同步限制平台管理页面，但只是界面控制；接口权限仍由后端独立验证。
      role === 0 &&
      [
        '/admin/userManagement',
        '/admin/rechargesManagement',
        '/admin/adminManagement',
        '/admin/auditLogs',
      ].includes(to.path)
    )
      return '/admin/charts'
    if (to.path.startsWith('/user') && role !== -1) return '/admin'
    return true
  } catch {
    if (to.path === '/user/paymentResult' || to.path === '/user/account')
      rememberPaymentReturn(to.query)
    tokenStore.removeToken()
    return '/login'
  }
})

//导出路由
export default router
