import axios from 'axios'
import { showRequestError, markHandled, safeMessage } from './request-error-notice'
import router from '@/router'
import { useTokenStore } from '@/stores/token'
import useUserInfoStore from '@/stores/userInfo'
import useAdminInfoStore from '@/stores/adminInfo'
import { httpErrorMessage } from './http-errors'

export const baseURL = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')
let unauthorizedShown = false
const request = axios.create({ baseURL, timeout: 30000 })
async function decodeBlob(data, contentType) {
  if (!(data instanceof Blob) || !/json/i.test(data.type || contentType || '')) return data
  try {
    return JSON.parse(await data.text())
  } catch {
    return { code: 1, message: '返回数据异常，请重试' }
  }
}
function rejectBusiness(response, data) {
  const error = data && typeof data === 'object' ? data : { code: 1 }
  error.message = safeMessage(error.message)
  error.requestId = response.headers?.['x-request-id']
  if (response.config.feedback !== false) showRequestError(error.message, error.requestId, error)
  else markHandled(error)
  return Promise.reject(error)
}
request.interceptors.request.use(
  (config) => {
    const { token } = useTokenStore()
    if (token) config.headers.Authorization = token
    return config
  },
  (error) => Promise.reject(error),
)
request.interceptors.response.use(
  async (response) => {
    if (response.config.responseType === 'blob') {
      const data = await decodeBlob(response.data, response.headers?.['content-type'])
      if (data instanceof Blob) return response
      return rejectBusiness(response, data)
    }
    if (response.data?.code === 0) return response.data
    return rejectBusiness(response, response.data)
  },
  async (error) => {
    if (error.code === 'ERR_CANCELED') return Promise.reject(error)
    if (error.response)
      error.response.data = await decodeBlob(
        error.response.data,
        error.response.headers?.['content-type'],
      )
    error.message = safeMessage(httpErrorMessage(error))
    error.requestId = error.response?.headers?.['x-request-id']
    if (error?.response?.status === 401) {
      useTokenStore().removeToken()
      useUserInfoStore().removeInfo()
      useAdminInfoStore().removeInfo()
      if (router.currentRoute.value.path !== '/login') router.replace('/login')
    }
    // 取消请求不弹错误；只有 401 清会话，缓存或登录基础设施故障返回的 503 保留会话供重试。
    const unauthorized = error?.response?.status === 401
    if ((unauthorized && !unauthorizedShown) || (!unauthorized && error.config?.feedback !== false))
      showRequestError(error.message, error.requestId, error)
    else markHandled(error)
    if (error?.response?.status === 401) {
      unauthorizedShown = true
      setTimeout(() => (unauthorizedShown = false), 2000)
    }
    return Promise.reject(error)
  },
)
export default request
