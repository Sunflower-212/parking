export function httpErrorMessage(error) {
  if (error?.response?.status === 401) return '登录已失效，请重新登录'
  if (error?.response?.status === 403) return '权限不足'
  return error?.response?.data?.message || (error?.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : '网络或服务异常，请稍后重试')
}
