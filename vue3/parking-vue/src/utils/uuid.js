export function randomUUID() {
  const crypto = globalThis.crypto
  if (typeof crypto?.randomUUID === 'function') return crypto.randomUUID()
  // HTTP IP 访问没有 randomUUID，使用同样的安全随机源兼容 UUID v4。
  if (typeof crypto?.getRandomValues !== 'function')
    throw new Error('浏览器不支持安全随机数，请更新浏览器或使用 HTTPS 访问')
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
