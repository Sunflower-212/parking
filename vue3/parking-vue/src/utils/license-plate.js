export const provinces = [...'京津沪渝冀晋蒙辽吉黑苏浙皖闽赣鲁豫鄂湘粤桂琼川贵云藏陕甘青宁新']
export const letters = [...'ABCDEFGHJKLMNPQRSTUVWXYZ']
export const digits = [...'0123456789']
export const plateTypes = [
  { value: 'ordinary', label: '普通车牌', length: 7 },
  { value: 'small', label: '小型新能源', length: 8 },
  { value: 'large', label: '大型新能源', length: 8 },
]
const prefix = `[${provinces.join('')}][A-HJ-NP-Z]-`
const patterns = {
  ordinary: new RegExp(`^${prefix}(?=[A-HJ-NP-Z0-9]{5}$)[0-9]*(?:[A-HJ-NP-Z][0-9]*){0,2}$`),
  small: new RegExp(`^${prefix}[A-HJ-NP-Z][A-HJ-NP-Z0-9][0-9]{4}$`),
  large: new RegExp(`^${prefix}[0-9]{5}[A-HJ-NP-Z]$`),
}
export function normalizePlate(value) {
  const text = String(value || '')
    .trim()
    .toUpperCase()
  return new RegExp(`^[${provinces.join('')}][A-HJ-NP-Z][A-HJ-NP-Z0-9]{5,6}$`).test(text)
    ? text.slice(0, 2) + '-' + text.slice(2)
    : text
}
export function plateType(value) {
  if (typeof value !== 'string' || !value) return null
  return plateTypes.find((t) => patterns[t.value].exec(value || '')?.[0] === value)?.value || null
}
export function validPlate(value, type) {
  if (typeof value !== 'string' || !value) return false
  return type ? patterns[type]?.exec(value || '')?.[0] === value : !!plateType(value)
}
export function plateCharacters(type, index, cells = []) {
  if (index === 0) return provinces
  if (index === 1) return letters
  if (type === 'small')
    return index === 2 ? letters : index === 3 ? [...letters, ...digits] : digits
  if (type === 'large') return index === 7 ? letters : digits
  const others = cells.slice(2).filter((c, i) => i + 2 !== index && letters.includes(c)).length
  return others >= 2 ? digits : [...letters, ...digits]
}
export function formatPlate(cells) {
  return (
    cells
      .map((c) => c || ' ')
      .join('')
      .slice(0, 2) +
    '-' +
    cells
      .map((c) => c || ' ')
      .join('')
      .slice(2)
  )
}
