import { randomUUID } from './uuid'

export function membershipAttempt(userId, planCode) {
  const key = `parking-membership:${userId}`
  const saved = readMembershipAttempt(userId)
  if (saved?.planCode === planCode) return saved
  const attempt = { planCode, key: randomUUID() }
  sessionStorage.setItem(key, JSON.stringify(attempt))
  return attempt
}
export function readMembershipAttempt(userId) {
  try {
    const value = JSON.parse(sessionStorage.getItem(`parking-membership:${userId}`) || 'null')
    return typeof value?.key === 'string' &&
      ['MONTHLY', 'QUARTERLY', 'YEARLY'].includes(value.planCode)
      ? value
      : null
  } catch {
    return null
  }
}
export function clearMembershipAttempt(userId) {
  sessionStorage.removeItem(`parking-membership:${userId}`)
}
export function membershipEnd(end, months, current = new Date()) {
  const now = new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  })
    .format(current)
    .replace(' ', 'T')
  const base = end && end > now ? end : now
  const date = new Date(`${base.slice(0, 19)}Z`)
  const month = date.getUTCMonth() + months
  const lastDay = new Date(Date.UTC(date.getUTCFullYear(), month + 1, 0)).getUTCDate()
  const day = Math.min(date.getUTCDate(), lastDay)
  date.setUTCDate(1)
  date.setUTCMonth(month)
  date.setUTCDate(day)
  return date.toISOString().slice(0, 19)
}
export function paymentSuccessMessage(status) {
  if (status?.membershipOrder) {
    if (status.membershipOrder.state === 'CLOSED') return '补款已转入余额，可重新购买会员'
    if (status.membershipOrder.state === 'SUCCEEDED')
      return status.membershipOrder.renewed ? '会员已续期' : '会员已开通'
  }
  return '充值成功，余额已更新'
}
