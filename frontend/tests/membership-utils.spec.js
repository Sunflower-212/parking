import { expect, it, afterEach } from 'vitest'
import {
  membershipEnd,
  membershipAttempt,
  clearMembershipAttempt,
  paymentSuccessMessage,
} from '../src/utils/membership'

afterEach(() => sessionStorage.clear())
it('clamps calendar months and uses Shanghai business time', () => {
  const now = new Date('2026-01-31T02:00:00Z')
  expect(membershipEnd(null, 1, now)).toBe('2026-02-28T10:00:00')
  expect(membershipEnd(null, 3, now)).toBe('2026-04-30T10:00:00')
  expect(membershipEnd(null, 12, new Date('2024-02-29T02:00:00Z'))).toBe('2025-02-28T10:00:00')
  expect(membershipEnd('2026-03-15T12:00:00', 1, now)).toBe('2026-04-15T12:00:00')
  expect(membershipEnd('2025-12-31T10:00:00', 1, now)).toBe('2026-02-28T10:00:00')
})
it('keeps retries scoped to the user and clears completed purchases', () => {
  const attempt = membershipAttempt(1, 'MONTHLY')
  expect(membershipAttempt(1, 'MONTHLY')).toEqual(attempt)
  expect(membershipAttempt(2, 'MONTHLY').key).not.toBe(attempt.key)
  clearMembershipAttempt(1)
  expect(membershipAttempt(1, 'MONTHLY').key).not.toBe(attempt.key)
})
it('distinguishes late supplements, activation, renewal, and ordinary recharge', () => {
  expect(paymentSuccessMessage({ membershipOrder: { state: 'CLOSED' } })).toContain('转入余额')
  expect(paymentSuccessMessage({ membershipOrder: { state: 'SUCCEEDED', renewed: false } })).toBe(
    '会员已开通',
  )
  expect(paymentSuccessMessage({ membershipOrder: { state: 'SUCCEEDED', renewed: true } })).toBe(
    '会员已续期',
  )
  expect(paymentSuccessMessage({})).toBe('充值成功，余额已更新')
})
