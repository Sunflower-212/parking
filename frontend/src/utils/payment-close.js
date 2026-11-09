import { ElMessageBox } from 'element-plus'
import request from './request'
import { money } from './format'

export function paymentDisplayState(row) {
  if (row.business_type === 'MEMBERSHIP_DEBIT') return 'DEBIT'
  if (row.flag === 1) return 'PAID'
  if (row.flag === 2) return 'CLOSED'
  return row.displayState || row.payment_state || 'VERIFYING'
}
export function canClosePayment(row) {
  return row.pay_method === 0 && !['PAID', 'CLOSED', 'DEBIT'].includes(paymentDisplayState(row))
}
export async function confirmPaymentClose(id, amount, membership = false) {
  try {
    await ElMessageBox.confirm(
      `关闭充值单 #${id}（${money(amount)}）后不能继续支付。此前付款核实后仍会入账。${membership ? '会员预留余额将释放，已付款的补差核实后转入余额。' : ''}`,
      '确认手动关单',
      { confirmButtonText: '确认关单', cancelButtonText: '暂不关闭', type: 'warning' },
    )
    return true
  } catch (error) {
    if (error === 'cancel' || error === 'close') return false
    throw error
  }
}
export async function closePayment(id) {
  try {
    return (
      await request.post('/user/cancelRecharges', new URLSearchParams({ rechargesId: id }), {
        feedback: false,
      })
    ).data
  } catch (error) {
    // A lost response does not prove the transaction failed or succeeded.
    const status = await request
      .get(`/user/recharge/${id}/status`, { feedback: false })
      .catch(() => null)
    if (['CLOSED', 'PAID'].includes(status?.data?.paymentState)) return status.data
    throw error
  }
}
