<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  plateTypes,
  plateType,
  validPlate,
  plateCharacters,
  formatPlate,
} from '@/utils/license-plate'
const props = defineProps({ modelValue: { type: String, default: '' }, disabled: Boolean })
const emit = defineEmits(['update:modelValue'])
const type = ref('ordinary'),
  cells = ref(Array(7).fill('')),
  active = ref(0),
  opened = ref(false),
  legacy = ref(''),
  changing = ref(false),
  root = ref()
let emitted
watch(
  () => props.modelValue,
  (value) => {
    if (value === emitted) return
    const detected = plateType(value)
    type.value = detected || 'ordinary'
    cells.value = detected ? [...value.replace('-', '')] : Array(7).fill('')
    legacy.value = value && !detected ? value : ''
    active.value = 0
  },
  { immediate: true },
)
const keys = computed(() => plateCharacters(type.value, active.value, cells.value))
function publish() {
  legacy.value = ''
  emitted = formatPlate(cells.value)
  emit('update:modelValue', emitted)
}
function focusCell() {
  nextTick(() => root.value?.querySelector(`[data-cell="${active.value}"]`)?.focus())
}
function select(index) {
  active.value = index
  opened.value = true
}
function choose(character) {
  cells.value[active.value] = character
  publish()
  active.value = Math.min(active.value + 1, cells.value.length - 1)
}
function backspace() {
  if (!cells.value[active.value] && active.value > 0) active.value--
  cells.value[active.value] = ''
  publish()
}
function clear() {
  cells.value = Array(cells.value.length).fill('')
  active.value = 0
  publish()
  opened.value = true
  focusCell()
}
async function changeType(value) {
  if (value === type.value || changing.value) return
  changing.value = true
  try {
    if (legacy.value || cells.value.some(Boolean))
      await ElMessageBox.confirm('切换类型将保留前两位，清空后续号码，是否继续？', '切换车牌类型', {
        confirmButtonText: '继续',
        cancelButtonText: '取消',
      })
    const prefix = legacy.value ? ['', ''] : cells.value.slice(0, 2)
    type.value = value
    cells.value = [
      ...prefix,
      ...Array(plateTypes.find((t) => t.value === value).length - 2).fill(''),
    ]
    active.value = prefix.every(Boolean) ? 2 : 0
    publish()
    opened.value = true
    focusCell()
  } catch {
    /* Cancel preserves the complete number. */
  } finally {
    changing.value = false
  }
}
function validate() {
  if (!legacy.value && validPlate(formatPlate(cells.value), type.value)) return true
  active.value = cells.value.findIndex(
    (c, i) => !plateCharacters(type.value, i, cells.value).includes(c),
  )
  if (active.value < 0) active.value = 0
  opened.value = true
  focusCell()
  return false
}
function finish() {
  if (validate()) {
    opened.value = false
    focusCell()
  }
}
defineExpose({ validate })
</script>
<template>
  <div ref="root" class="plate-input">
    <div class="plate-type-label">
      车牌类型
      <el-select
        :model-value="type"
        :validate-event="false"
        aria-label="车牌类型"
        :disabled="disabled || changing"
        @change="changeType"
      >
        <el-option
          v-for="item in plateTypes"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
    </div>
    <div class="plate-number-label">车牌号</div>
    <p v-if="legacy" class="plate-legacy" role="alert">
      当前号码：{{ legacy }}。格式暂不支持，请核对后重新选择；原值尚未修改。
    </p>
    <div
      class="plate-cells"
      :class="{ energy: type !== 'ordinary' }"
      role="group"
      aria-label="车牌号"
    >
      <template v-for="(cell, index) in cells" :key="index">
        <span v-if="index === 2" class="plate-separator" aria-hidden="true">·</span>
        <button
          type="button"
          :data-cell="index"
          :class="{ selected: opened && active === index, placeholder: !cell && index === 0 }"
          :aria-label="'车牌第' + (index + 1) + '位：' + (cell || '未选择')"
          :aria-pressed="opened && active === index"
          :disabled="disabled"
          @click="select(index)"
        >
          {{ cell || (index === 0 ? '省' : '') }}
        </button>
      </template>
    </div>
    <div v-if="opened" class="plate-keyboard" role="group" aria-label="车牌字符键盘">
      <div class="plate-keys">
        <button
          v-for="key in keys"
          :key="key"
          type="button"
          :disabled="disabled"
          :aria-label="'输入' + key"
          @click="choose(key)"
        >
          {{ key }}
        </button>
      </div>
      <div class="plate-key-actions">
        <el-button :disabled="disabled" @click="backspace">退格</el-button
        ><el-button :disabled="disabled" @click="clear">清空</el-button
        ><el-button type="primary" :disabled="disabled" @click="finish">完成</el-button>
      </div>
    </div>
  </div>
</template>
<style scoped>
.plate-input {
  width: 100%;
  min-width: 0;
}
.plate-type-label {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
  color: var(--el-text-color-regular);
}
.plate-number-label {
  margin-bottom: 8px;
  color: var(--el-text-color-regular);
}
.plate-cells {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  max-width: 450px;
}
.plate-cells button {
  flex: 1;
  min-width: 0;
  height: 44px;
  border: 1px solid #c9d8ee;
  border-radius: 7px;
  background: #f3f7ff;
  color: #183e79;
  font-weight: 600;
  font-size: 18px;
  font-family: inherit;
  cursor: pointer;
  padding: 0;
}
.plate-cells.energy button {
  background: #f0faf4;
  border-color: #bddcc9;
}
.plate-cells button.placeholder {
  color: #9aa6b7;
  font-weight: 400;
}
.plate-cells button.selected {
  border: 2px solid #2563eb;
  background: #e7efff;
}
.plate-cells button:focus-visible,
.plate-keys button:focus-visible {
  outline: 3px solid #93b4ff;
  outline-offset: 2px;
}
.plate-separator {
  width: 5px;
  text-align: center;
}
.plate-keyboard {
  margin-top: 12px;
  padding: 10px;
  background: #f4f7fc;
  border: 1px solid #e1e8f2;
  border-radius: 10px;
}
.plate-keys {
  display: grid;
  grid-template-columns: repeat(8, minmax(0, 1fr));
  gap: 5px;
}
.plate-keys button {
  height: 38px;
  border: 1px solid #dce4ee;
  border-radius: 6px;
  background: white;
  font-size: 16px;
  cursor: pointer;
  color: #24364f;
}
.plate-key-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
  gap: 8px;
}
.plate-key-actions .el-button + .el-button {
  margin-left: 0;
}
.plate-legacy {
  color: var(--el-color-warning-dark-2);
  overflow-wrap: anywhere;
  font-size: 13px;
  line-height: 1.6;
}
.plate-input button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
@media (max-width: 480px) {
  .plate-keys {
    grid-template-columns: repeat(7, minmax(0, 1fr));
  }
  .plate-cells button {
    height: 40px;
    font-size: 16px;
  }
}
</style>
