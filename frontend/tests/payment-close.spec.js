import { beforeEach, expect, it, vi } from 'vitest'
import { ElMessageBox } from 'element-plus'
import request from '../src/utils/request'
import {
  paymentDisplayState,
  canClosePayment,
  confirmPaymentClose,
  closePayment,
} from '../src/utils/payment-close'
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: vi.fn() } }))
vi.mock('../src/utils/request', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
beforeEach(() => vi.resetAllMocks())
it('prioritizes local closed/paid/debit over background review', () => {
  for (const [flag, expected] of [
    [0, 'REVIEW'],
    [1, 'PAID'],
    [2, 'CLOSED'],
  ]) {
    const row = { flag, pay_method: 0, payment_state: 'REVIEW' }
    expect(paymentDisplayState(row)).toBe(expected)
    expect(canClosePayment(row)).toBe(flag === 0)
  }
  expect(paymentDisplayState({ flag: 1, business_type: 'MEMBERSHIP_DEBIT' })).toBe('DEBIT')
  expect(canClosePayment({ flag: 0, pay_method: 1 })).toBe(false)
})
it('confirms amount and reservation consequences without sending a close on dismissal', async () => {
  ElMessageBox.confirm.mockRejectedValue('cancel')
  expect(await confirmPaymentClose(17, 88, true)).toBe(false)
  expect(ElMessageBox.confirm.mock.calls[0][0]).toContain('#17')
  expect(ElMessageBox.confirm.mock.calls[0][0]).toContain('88.00')
  expect(ElMessageBox.confirm.mock.calls[0][0]).toContain('预留余额将释放')
  expect(request.post).not.toHaveBeenCalled()
})
it('accepts lost response only after a local terminal state is read back', async () => {
  const lost = new Error('network')
  request.post.mockRejectedValue(lost)
  for (const state of ['CLOSED', 'PAID']) {
    request.get.mockResolvedValue({ data: { id: 17, paymentState: state } })
    expect((await closePayment(17)).paymentState).toBe(state)
  }
  request.get.mockResolvedValue({ data: { paymentState: 'REVIEW' } })
  await expect(closePayment(17)).rejects.toBe(lost)
  request.get.mockRejectedValue(new Error('offline'))
  await expect(closePayment(17)).rejects.toBe(lost)
})
it('uses the mutation result without redundant polling', async () => {
  request.post.mockResolvedValue({ data: { id: 17, paymentState: 'CLOSED' } })
  expect((await closePayment(17)).paymentState).toBe('CLOSED')
  expect(request.post.mock.calls[0][1].get('rechargesId')).toBe('17')
  expect(request.get).not.toHaveBeenCalled()
})
