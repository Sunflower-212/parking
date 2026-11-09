export function rechargeId(query = {}) {
  const value = query.rechargeId ?? query.out_trade_no
  return typeof value === 'string' && /^[1-9]\d{0,9}$/.test(value) && Number(value) <= 2147483647
    ? Number(value)
    : null
}
export const paymentPath = (id) => `/user/paymentResult?rechargeId=${id}`
export function rememberPayment(userId, payment) {
  sessionStorage.setItem(`parking-payment:${userId}`, JSON.stringify({ id: payment.rechargeId }))
}
export function pendingPayment(userId) {
  try {
    const saved = JSON.parse(sessionStorage.getItem(`parking-payment:${userId}`) || 'null')
    return rechargeId({ rechargeId: String(saved?.id || '') })
  } catch {
    return null
  }
}
export function clearPayment(userId, id) {
  if (pendingPayment(userId) === id) sessionStorage.removeItem(`parking-payment:${userId}`)
}
export function rememberPaymentReturn(query) {
  const id = rechargeId(query)
  if (id) sessionStorage.setItem('parking-payment-return', String(id))
}
export function consumePaymentReturn() {
  const id = rechargeId({ rechargeId: sessionStorage.getItem('parking-payment-return') })
  sessionStorage.removeItem('parking-payment-return')
  return id ? paymentPath(id) : null
}
