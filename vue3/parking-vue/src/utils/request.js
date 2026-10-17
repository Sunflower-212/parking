import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useTokenStore } from '@/stores/token'
import useUserInfoStore from '@/stores/userInfo'
import useAdminInfoStore from '@/stores/adminInfo'
import { httpErrorMessage } from './http-errors'

export const baseURL = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')
let unauthorizedShown = false
const request = axios.create({ baseURL, timeout: 30000 })
request.interceptors.request.use(
  (config) => {
    const { token } = useTokenStore()
    if (token) config.headers.Authorization = token
    return config
  },
  (error) => Promise.reject(error),
)
request.interceptors.response.use(
  (response) => {
    if (response.config.responseType === 'blob') return response
    if (response.data.code === 0) return response.data
    ElMessage.error(response.data.message || '操作失败')
    return Promise.reject(response.data)
  },
  (error) => {
    if (error?.response?.status === 401) {
      useTokenStore().removeToken()
      useUserInfoStore().removeInfo()
      useAdminInfoStore().removeInfo()
      if (router.currentRoute.value.path !== '/login') router.replace('/login')
    }
    if (error.code === 'ERR_CANCELED') return Promise.reject(error)
    if (error?.response?.status !== 401 || !unauthorizedShown)
      ElMessage.error(httpErrorMessage(error))
    if (error?.response?.status === 401) {
      unauthorizedShown = true
      setTimeout(() => (unauthorizedShown = false), 2000)
    }
    return Promise.reject(error)
  },
)
export default request
