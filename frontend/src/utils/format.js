export function dateTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}
export function money(value) {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(
    Number(value || 0),
  )
}
export function cleanQuery(values) {
  return Object.fromEntries(
    Object.entries(values).filter(([, v]) => v !== null && v !== undefined && v !== ''),
  )
}
export function roleName(role) {
  return role === 1 ? '超级管理员' : role === 0 ? '停车场管理员' : '普通用户'
}
