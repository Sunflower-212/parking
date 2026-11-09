import { afterEach, expect, it, vi } from 'vitest'

vi.mock('../src/router', () => ({
  default: { currentRoute: { value: { path: '/login' } }, replace: vi.fn() },
}))
vi.mock('../src/stores/token', () => ({ useTokenStore: () => ({ token: '' }) }))
vi.mock('../src/stores/userInfo', () => ({ default: () => ({ removeInfo: vi.fn() }) }))
vi.mock('../src/stores/adminInfo', () => ({ default: () => ({ removeInfo: vi.fn() }) }))

afterEach(() => vi.unstubAllEnvs())

it.each([
  ['/', '', '/api'],
  ['/parking/', '', '/parking/api'],
  ['/parking/', 'https://api.example.invalid/custom/', 'https://api.example.invalid/custom'],
])('resolves API base from %s and explicit override %s', async (base, override, expected) => {
  vi.resetModules()
  vi.stubEnv('BASE_URL', base)
  vi.stubEnv('VITE_API_BASE_URL', override)
  const { default: request, baseURL } = await import('../src/utils/request')
  expect(baseURL).toBe(expected)
  expect(request.getUri({ url: '/admin/login' })).toBe(`${expected}/admin/login`)
})
