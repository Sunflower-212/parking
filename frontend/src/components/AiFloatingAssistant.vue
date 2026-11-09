<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound, Close, Clock, Plus, Delete } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { aiChat, aiHistory, aiSessions, deleteAiSession } from '@/api/ai'
import { randomUUID } from '@/utils/uuid'
import { useTokenStore } from '@/stores/token'
import {
  reservationService,
  cancelReservationService,
  isEnterOrReservationService,
} from '@/api/enter'
import { feedback, localFailure } from '@/utils/request-error-notice'
import AiMarkdown from './AiMarkdown.vue'

const props = defineProps({ admin: Boolean })
const admin = computed(() => props.admin)
const route = useRoute(),
  router = useRouter()
const open = ref(false),
  initialized = ref(false),
  unread = ref(false),
  showHistory = ref(false)
const launcher = ref(null),
  input = ref(null),
  messageList = ref(null)
const target = ref(document.fullscreenElement || document.body),
  viewport = ref({})
const status = ref(null),
  draft = ref(''),
  messages = ref([]),
  history = ref([]),
  chatId = ref('')
const busy = ref(false),
  loading = ref(false),
  progress = ref(''),
  expired = ref(false),
  lastFailed = ref('')
const actionBusy = ref(false), deleting = ref(false)
const windowElement = ref(null), size = ref(null)
let resizeStart = null
const windowStyle = computed(() => size.value ? { width: `${size.value.width}px`, height: `${size.value.height}px` } : {})
const dateFormat = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
function dateText(value, short = false) {
  if (!value || Number.isNaN(Date.parse(value))) return ''
  const text = dateFormat.format(new Date(value))
  return short ? text.slice(-5) : text
}
function clampSize(width, height) {
  const maxWidth = Math.max(1, innerWidth - (innerWidth <= 767 ? 24 : 48))
  const maxHeight = Math.max(1, (window.visualViewport?.height || innerHeight) - (innerWidth <= 767 ? 100 : 120))
  size.value = { width: Math.max(Math.min(320, maxWidth), Math.min(maxWidth, width)), height: Math.max(Math.min(300, maxHeight), Math.min(maxHeight, height)) }
}
function startResize(event, edge) {
  if (event.button !== 0) return
  event.preventDefault()
  const box = windowElement.value.getBoundingClientRect()
  resizeStart = { x: event.clientX, y: event.clientY, width: box.width, height: box.height, edge }
  event.currentTarget.setPointerCapture(event.pointerId)
}
function resize(event) {
  if (!resizeStart) return
  const r = resizeStart
  clampSize(r.width + (r.edge !== 'top' ? r.x - event.clientX : 0), r.height + (r.edge !== 'left' ? r.y - event.clientY : 0))
}
function resizeKey(event, edge) {
  if (!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(event.key)) return
  event.preventDefault()
  const box = windowElement.value.getBoundingClientRect()
  clampSize(box.width + (edge !== 'top' ? ({ArrowLeft:20,ArrowRight:-20}[event.key] || 0) : 0), box.height + (edge !== 'left' ? ({ArrowUp:20,ArrowDown:-20}[event.key] || 0) : 0))
}
let controller,
  disposed = false,
  generation = 0
const available = computed(() => status.value?.enabled && status.value?.configured)
const token = useTokenStore()
const identityToken = token.token
const stale = () => disposed || token.token !== identityToken
function stop() {
  controller?.abort()
}
function newChat() {
  showHistory.value = false
  stop()
  chatId.value = randomUUID()
  messages.value = []
  expired.value = false
  lastFailed.value = ''
}
async function loadHistory() {
  const currentGeneration = generation
  const stale = () => disposed || token.token !== identityToken || currentGeneration !== generation
  const result = await aiSessions()
  if (!stale()) history.value = result.data
}
async function initialize() {
  const currentGeneration = generation
  const stale = () => disposed || token.token !== identityToken || currentGeneration !== generation

  if (loading.value) return
  loading.value = true
  try {
    const result = await request.get('/ai/status')
    if (stale()) return
    status.value = result.data
    if (available.value) await loadHistory()
    if (stale()) return
    if (!chatId.value) newChat()
    initialized.value = true
  } catch {
  } finally {
    if (!stale()) loading.value = false
  }
}
async function selectHistory(id) {
  const currentGeneration = generation
  const stale = () => disposed || token.token !== identityToken || currentGeneration !== generation

  if (busy.value || loading.value || deleting.value) return
  loading.value = true
  controller = new AbortController()
  try {
    const result = await aiHistory(id, controller.signal)
    if (stale()) return
    showHistory.value = false
    chatId.value = id
    messages.value = result.items
    expired.value = result.expired
    lastFailed.value = ''
  } catch {
  } finally {
    if (!stale()) loading.value = false
  }
}
async function send(text = draft.value) {
  const currentGeneration = generation
  const stale = () => disposed || token.token !== identityToken || currentGeneration !== generation

  if (
    actionBusy.value ||
    deleting.value ||
    busy.value ||
    loading.value ||
    !available.value ||
    !text.trim() ||
    text.length > 8000
  )
    return
  if (!chatId.value) newChat()
  busy.value = true
  lastFailed.value = ''
  progress.value = '正在连接'
  expired.value = false
  messages.value.push({ role: 'user', content: text })
  const index =
    messages.value.push({ role: 'assistant', content: '', cards: [], complete: false }) - 1
  const reply = messages.value[index]
  controller = new AbortController()
  draft.value = ''
  try {
    await aiChat({ chatId: chatId.value, message: text }, controller.signal, (event) => {
      if (stale()) return
      if (event.type === 'delta') reply.content += event.text
      if (event.type === 'message_time' && ['user','assistant'].includes(event.role)) {
        messages.value[event.role === 'user' ? index - 1 : index].timestamp = event.timestamp
      }
      if (event.type === 'progress') progress.value = event.message
      if (
        event.type === 'action' &&
        !admin.value &&
        ['reserve', 'cancel'].includes(event.data?.action)
      ) {
        const key = `${event.data.action}:${event.data.spaceId || event.data.reservationId}`
        if (!reply.cards.some((c) => c.key === key))
          reply.cards.push({ ...event.data, key, busy: false, finished: false })
      }
      if (event.type === 'done') reply.complete = event.success === true
    })
    if (stale()) return
    if (!reply.complete) throw new Error('回答未完成')
    await loadHistory()
  } catch (error) {
    if (stale()) return
    reply.cards = []
    reply.notice = error.name === 'AbortError' ? '已停止生成' : '回答未完成，可重新提问'
    lastFailed.value = text
    if (!draft.value) draft.value = text
  } finally {
    if (!stale()) {
      busy.value = false
      progress.value = ''
      if (!open.value) unread.value = true
    }
  }
}
async function removeHistory(id) {
  if (deleting.value || busy.value || loading.value || actionBusy.value) return
  const currentGeneration = generation
  deleting.value = true
  try {
    await deleteAiSession(id)
    if (stale() || currentGeneration !== generation) return
    history.value = history.value.filter(item => item.chatId !== id)
    if (chatId.value === id) {
      generation++
      newChat()
      draft.value = ''
      unread.value = false
      showHistory.value = true
    }
  } catch { /* The shared request handler displays the failure. */ }
  finally { if (!stale()) deleting.value = false }
}
async function confirm(card) {
  const currentGeneration = generation
  const stale = () => disposed || token.token !== identityToken || currentGeneration !== generation

  if (actionBusy.value || card.finished || busy.value || admin.value) return
  card.busy = true
  actionBusy.value = true
  const showEntered = (current) => {
    if (!current.some((entry) => entry.flag === 1)) return false
    card.finished = true
    card.result = '车辆已入场，不能再次预约或取消预约'
    window.dispatchEvent(new Event('parking-reservation-changed'))
    return true
  }
  try {
    const current = (await isEnterOrReservationService()).data || []
    if (stale()) return
    if (showEntered(current)) return
    if (card.action === 'reserve') {
      if (
        !current.some((e) => e.space_id === card.spaceId && e.flag === 0 && e.reservation === 1)
      ) {
        const fresh = (
          await request.get('/spaces/availability', {
            params: { park_id: card.parkingId, spaces_storey: card.floor },
          })
        ).data
        if (stale()) return
        if (!fresh?.some((s) => s.id === card.spaceId && s.spaces_flag === 0))
          throw new Error('车位状态已变化，请重新查询')
        await reservationService({ id: card.spaceId })
      }
    } else if (
      current.some((e) => e.id === card.reservationId && e.flag === 0 && e.reservation === 1)
    )
      await cancelReservationService(card.reservationId)
    else throw new Error('预约状态已变化，请重新查询')
    if (stale()) return
    const latest = (await isEnterOrReservationService()).data || []
    if (stale()) return
    if (showEntered(latest)) return
    card.finished = true
    card.result = card.action === 'reserve' ? '预约成功' : '已取消预约'
    feedback.success(card.result)
    window.dispatchEvent(new Event('parking-reservation-changed'))
  } catch (error) {
    if (stale()) return
    // A response can be lost after commit. Read facts before offering another write.
    try {
      const current = (await isEnterOrReservationService()).data || []
      if (stale()) return
      if (showEntered(current)) return
      if (
        card.action === 'reserve'
          ? current.some((e) => e.space_id === card.spaceId && e.flag === 0 && e.reservation === 1)
          : !current.some((e) => e.id === card.reservationId)
      ) {
        card.finished = true
        card.result =
          card.action === 'reserve' ? '已确认预约成功' : '该预约已不在待预约列表，请查看预约状态'
        window.dispatchEvent(new Event('parking-reservation-changed'))
        return
      }
    } catch {}
    localFailure(error, error.message || '操作未确认，请查看当前预约后重试')
  } finally {
    if (!stale()) {
      card.busy = false
      actionBusy.value = false
    }
  }
}

function syncViewport() {
  if (size.value) clampSize(size.value.width, size.value.height)
  const view = window.visualViewport
  viewport.value = {
    '--ai-height': `${view?.height || window.innerHeight}px`,
    '--ai-bottom': `${Math.max(0, window.innerHeight - (view?.height || window.innerHeight) - (view?.offsetTop || 0))}px`,
  }
}
function fullChanged() {
  target.value = document.fullscreenElement || document.body
  syncViewport()
}
async function expand() {
  open.value = true
  unread.value = false
  if (!initialized.value) await initialize()
  await nextTick()
  if (open.value && !stale()) input.value?.focus()
}
async function collapse() {
  open.value = false
  await nextTick()
  launcher.value?.focus()
}
function escape(event) {
  if (event.key === 'Escape' && open.value) collapse()
}
function leave() {
  generation++
  size.value = null
  resizeStart = null
  deleting.value = false
  stop()
  busy.value = false
  loading.value = false
  actionBusy.value = false
  status.value = null
  lastFailed.value = ''
  showHistory.value = false
  messages.value = []
  history.value = []
  draft.value = ''
  chatId.value = ''
  initialized.value = false
  open.value = false
  unread.value = false
}
watch(
  () => route.query.ai,
  (value) => {
    if (value !== '1') return
    expand()
    const query = { ...route.query }
    delete query.ai
    router.replace({ path: route.path, query, hash: route.hash })
  },
  { immediate: true },
)
watch(
  () => messages.value.at(-1)?.content,
  async () => {
    const list = messageList.value
    if (!list || list.scrollHeight - list.scrollTop - list.clientHeight > 100) return
    await nextTick()
    list.scrollTop = list.scrollHeight
  },
)
onMounted(() => {
  syncViewport()
  document.addEventListener('fullscreenchange', fullChanged)
  document.addEventListener('keydown', escape)
  window.addEventListener('resize', syncViewport)
  window.addEventListener('pagehide', leave)
  window.visualViewport?.addEventListener('resize', syncViewport)
  window.visualViewport?.addEventListener('scroll', syncViewport)
})
onBeforeUnmount(() => {
  disposed = true
  leave()
  document.removeEventListener('fullscreenchange', fullChanged)
  document.removeEventListener('keydown', escape)
  window.removeEventListener('resize', syncViewport)
  window.removeEventListener('pagehide', leave)
  window.visualViewport?.removeEventListener('resize', syncViewport)
  window.visualViewport?.removeEventListener('scroll', syncViewport)
})
</script>

<template>
  <Teleport :to="target">
    <div
      class="ai-floating"
      :style="viewport"
      @pointerdown.stop
      @pointermove.stop
      @pointerup.stop
      @click.stop
      @wheel.stop
      @touchstart.stop
      @touchmove.stop
      @touchend.stop
    >
      <section
        ref="windowElement"
        :style="windowStyle"
        v-show="open"
        id="ai-chat-window"
        class="ai-window"
        role="dialog"
        aria-modal="false"
        aria-labelledby="ai-window-title"
      >
        <span v-for="edge in ['top','left','corner']" :key="edge" :class="['ai-resize', 'ai-resize-' + edge]"
          role="button" tabindex="0" :aria-label="{top:'调整聊天窗高度',left:'调整聊天窗宽度',corner:'调整聊天窗大小'}[edge]"
          @pointerdown="startResize($event, edge)" @pointermove="resize" @pointerup="resizeStart = null"
          @pointercancel="resizeStart = null" @lostpointercapture="resizeStart = null" @keydown="resizeKey($event, edge)" />
        <header class="ai-header">
          <strong id="ai-window-title" translate="no"
            ><el-icon><ChatDotRound /></el-icon> 系统助手-可乐</strong
          >
          <div class="ai-toolbar">
            <el-button
              text
              :icon="Clock"
              aria-label="历史会话"
              title="历史会话"
              :disabled="!available || busy || loading || actionBusy || deleting"
              @click="showHistory = !showHistory"
            />
            <el-button
              text
              :icon="Plus"
              aria-label="新建会话"
              title="新建会话"
              :disabled="busy || loading || actionBusy || deleting"
              @click="newChat"
            />
            <el-button
              text
              :icon="Close"
              aria-label="收起系统助手-可乐"
              title="收起后继续回答"
              @click="collapse"
            />
          </div>
        </header>
        <div v-if="showHistory" class="ai-history" aria-label="历史会话列表">
          <p v-if="!history.length">暂无会话</p>
          <div v-for="item in history" :key="item.chatId" class="ai-history-row">
            <button class="ai-history-select" :disabled="loading || deleting" @click="selectHistory(item.chatId)">
              <span class="ai-history-title">{{ item.title }}</span>
              <time :datetime="item.updatedAt">{{ dateText(item.updatedAt) }}</time>
              <span v-if="item.expired" class="ai-history-expired">已失效</span>
            </button>
            <el-popconfirm title="删除这段会话？" confirm-button-text="删除" cancel-button-text="取消" :teleported="false" @confirm="removeHistory(item.chatId)">
              <template #reference><el-button text :icon="Delete" :aria-label="'删除会话：' + item.title" :disabled="busy || loading || deleting || actionBusy" /></template>
            </el-popconfirm>
          </div>
        </div>
        <div v-if="loading" class="ai-state" role="status">正在加载…</div>
        <el-alert
          v-if="status && !available"
          type="info"
          show-icon
          :closable="false"
          :title="status.enabled ? '系统助手尚未配置，请联系管理员' : '系统助手尚未启用，请联系管理员'"
        />
        <el-button v-if="!status && !loading" @click="initialize">重新加载</el-button>
        <el-alert
          v-if="expired"
          type="info"
          :closable="false"
          show-icon
          title="此会话内容已过期或服务已重启，请重新提问"
        />
        <div ref="messageList" class="ai-messages" aria-label="聊天消息" aria-live="polite">
          <article v-for="(message, i) in messages" :key="i" :class="['ai-message', message.role]">
            <AiMarkdown v-if="message.role === 'assistant'" :content="message.content" />
            <p v-else>{{ message.content }}</p>
            <small v-if="message.notice">{{ message.notice }}</small>
            <div v-for="card in message.cards || []" :key="card.key" class="ai-action">
              <strong>{{ card.action === 'reserve' ? '确认预约车位' : '确认取消预约' }}</strong>
              <p>{{ card.parkingName }} · {{ card.floor }}层 · {{ card.spaceName }}</p>
              <small v-if="card.reservationId">预约编号 {{ card.reservationId }}</small>
              <p v-if="card.result" role="status">{{ card.result }}</p>
              <el-button
                v-else
                type="primary"
                :loading="card.busy"
                :disabled="busy || actionBusy || !message.complete"
                @click="confirm(card)"
                >{{ card.action === 'reserve' ? '确认预约' : '确认取消' }}</el-button
              >
            </div>
            <time v-if="message.timestamp" class="ai-message-time" :datetime="message.timestamp" :title="dateText(message.timestamp)">{{ dateText(message.timestamp, true) }}</time>
          </article>
        </div>
        <p v-if="busy" class="ai-progress" role="status">{{ progress || '正在回答' }}</p>
        <form v-if="available" class="ai-compose" @submit.prevent="send()">
          <el-input
            ref="input"
            v-model="draft"
            type="textarea"
            :rows="2"
            resize="none"
            maxlength="8000"
            show-word-limit
            aria-label="输入问题"
            placeholder="请输入问题，勿发送密码或密钥"
            :disabled="busy || loading"
          />
          <div class="ai-buttons">
            <el-button
              v-if="lastFailed && !busy"
              :disabled="loading || actionBusy"
              @click="send(lastFailed)"
              >重新提问</el-button
            ><el-button v-if="busy" @click="stop">停止生成</el-button
            ><el-button
              v-else
              native-type="submit"
              type="primary"
              :disabled="!draft.trim() || loading || actionBusy"
              >发送</el-button
            >
          </div>
        </form>
      </section>
      <span v-if="busy && !open" class="ai-floating-status" role="status">正在回答…</span>
      <button
        ref="launcher"
        class="ai-launcher"
        type="button"
        translate="no"
        title="系统助手-可乐"
        :class="{ working: busy }"
        :aria-expanded="open"
        aria-controls="ai-chat-window"
        :aria-label="
          open
            ? '收起系统助手-可乐'
            : busy
              ? '打开系统助手-可乐，正在回答'
              : unread
                ? '打开系统助手-可乐，有新消息'
                : '打开系统助手-可乐'
        "
        @click="open ? collapse() : expand()"
      >
        <svg class="ai-robot" viewBox="0 0 32 32" fill="none" aria-hidden="true" focusable="false">
          <path d="M16 7V4" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
          <circle cx="16" cy="3.5" r="2" fill="currentColor" />
          <rect x="5" y="8" width="22" height="19" rx="7" fill="currentColor" />
          <path d="M2.5 15v5M29.5 15v5" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
          <rect x="10" y="13" width="3" height="5" rx="1.5" fill="#2563eb" />
          <rect x="19" y="13" width="3" height="5" rx="1.5" fill="#2563eb" />
          <path d="M12.5 21c2 2 5 2 7 0" stroke="#2563eb" stroke-width="1.8" stroke-linecap="round" />
        </svg>
        <span v-if="unread && !open" class="ai-unread" aria-hidden="true" />
      </button>
    </div>
  </Teleport>
</template>
<style scoped>
.ai-floating {
  position: fixed;
  right: max(24px, env(safe-area-inset-right));
  bottom: calc(24px + env(safe-area-inset-bottom) + var(--ai-bottom, 0px));
  z-index: 2300;
  color: #182230;
  font:
    14px/1.5 system-ui,
    sans-serif;
}
.ai-launcher {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  box-sizing: border-box;
  flex-shrink: 0;
  width: 56px;
  height: 56px;
  border: 0;
  border-radius: 50%;
  background: linear-gradient(145deg, #478bff, #2563eb 60%, #1d4ed8);
  color: white;
  cursor: pointer;
  box-shadow: 0 6px 24px #2563eb55;
  margin-left: auto;
  position: relative;
}
.ai-floating-status {
  position: absolute;
  right: 68px;
  bottom: 14px;
  white-space: nowrap;
  padding: 5px 10px;
  border: 1px solid #dbe4f0;
  border-radius: 12px;
  background: white;
  color: #245bc1;
}
.ai-robot {
  display: block;
  width: 32px;
  height: 32px;
  flex: none;
}
.ai-launcher:focus-visible {
  outline: 3px solid #93c5fd;
  outline-offset: 3px;
}
.ai-launcher.working {
  box-shadow:
    0 0 0 4px #bfdbfe,
    0 6px 24px #2563eb55;
}
.ai-unread {
  position: absolute;
  right: 1px;
  top: 1px;
  width: 12px;
  height: 12px;
  border: 2px solid white;
  border-radius: 50%;
  background: #ef4444;
}
.ai-window {
  box-sizing: border-box;
  position: relative;
  display: flex;
  flex-direction: column;
  width: 400px;
  height: min(600px, calc(var(--ai-height, 100dvh) - 120px));
  margin-bottom: 12px;
  background: white;
  border: 1px solid #dbe4f0;
  border-radius: 18px;
  box-shadow: 0 12px 48px #152b5330;
  overflow: hidden;
}
.ai-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px 6px;
  flex-shrink: 0;
}
.ai-header > strong {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 16px;
  white-space: nowrap;
  min-width: 0;
}
.ai-header > strong .el-icon {
  color: #2563eb;
  font-size: 22px;
}
.ai-toolbar {
  display: flex;
  flex: none;
}
.ai-toolbar .el-button {
  width: 32px;
  height: 32px;
  padding: 8px;
}
.ai-toolbar .el-button + .el-button {
  margin-left: 0;
}
.ai-description {
  margin: 0;
  padding: 0 14px 10px;
  color: #667085;
  font-size: 12px;
  border-bottom: 1px solid #edf1f7;
  flex-shrink: 0;
}
.ai-state {
  padding: 12px;
}
.ai-history {
  padding: 8px 14px;
  max-height: 150px;
  overflow: auto;
  flex-shrink: 0;
  background: #f8fafc;
}
.ai-history button {
  display: block;
  border: 0;
  background: transparent;
  padding: 6px;
  color: #245bc1;
  cursor: pointer;
}
small {
  color: #667085;
}
.ai-messages {
  flex: 1;
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
  padding: 12px;
}
.ai-empty {
  color: #667085;
}
.ai-message {
  box-sizing: border-box;
  padding: 10px;
  margin: 0 0 12px;
  border-radius: 12px;
  background: #f5f7fa;
  overflow-wrap: anywhere;
}
.ai-message.user {
  background: #eaf2ff;
  margin-left: auto;
  width: fit-content;
  max-width: 85%;
}
.ai-message p {
  white-space: pre-wrap;
  margin: 6px 0;
}
.ai-action {
  padding: 10px;
  border: 1px solid #d5e3fc;
  border-radius: 10px;
  background: white;
  margin-top: 10px;
}
.ai-action .el-button {
  display: block;
  margin-top: 8px;
}
.ai-compose {
  padding: 10px 12px 12px;
  border-top: 1px solid #edf1f7;
  flex-shrink: 0;
}
.ai-buttons {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
.ai-progress {
  color: #667085;
  padding: 0 12px;
  margin: 4px 0;
  flex-shrink: 0;
}
@media (max-width: 767px) {
  .ai-floating {
    right: 12px;
    bottom: calc(12px + env(safe-area-inset-bottom) + var(--ai-bottom, 0px));
  }
  .ai-window {
    width: calc(100vw - 24px);
    height: min(600px, calc(var(--ai-height, 100dvh) - 100px - env(safe-area-inset-bottom)));
  }
}
</style>

<style scoped>
.ai-history-row { display: flex; align-items: center; gap: 4px; border-bottom: 1px solid #edf1f7; }
.ai-history .ai-history-select { flex: 1; min-width: 0; text-align: left; cursor: pointer; padding: 8px 0; }
.ai-history-title { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #182230; }
.ai-history time, .ai-history-expired { font-size: 11px; color: #667085; margin-right: 8px; }
.ai-message-time { display: block; text-align: right; margin-top: 6px; color: #667085; font-size: 11px; }
.ai-resize { position: absolute; z-index: 2; touch-action: none; }
.ai-resize-top { top: 0; left: 18px; right: 18px; height: 8px; cursor: ns-resize; }
.ai-resize-left { left: 0; top: 18px; bottom: 18px; width: 8px; cursor: ew-resize; }
.ai-resize-corner { top: 0; left: 0; width: 18px; height: 18px; cursor: nwse-resize; }
.ai-resize:focus-visible { outline: 2px solid #2563eb; outline-offset: -2px; }
</style>
