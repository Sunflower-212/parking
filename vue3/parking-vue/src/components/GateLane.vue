<script setup>
import { ref, nextTick, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
const props = defineProps({ mode: String, parkId: Number, enabled: Boolean }),
  video = ref(),
  canvas = ref(),
  devices = ref([]),
  deviceId = ref(''),
  stream = ref(),
  busy = ref(false),
  hasPhoto = ref(false),
  plate = ref(''),
  input = ref()
let disposed = false
function close() {
  stream.value?.getTracks().forEach((t) => t.stop())
  stream.value = null
  if (video.value) video.value.srcObject = null
}
async function camera() {
  if (stream.value) {
    close()
    return
  }
  if (busy.value) return
  if (!navigator.mediaDevices?.getUserMedia) {
    ElMessage.warning('当前浏览器不支持摄像头，请上传图片')
    return
  }
  busy.value = true
  try {
    // 摄像头授权可能在离开页面后才完成，取得轨道后还要检查组件状态，必要时立即停止轨道。
    const result = await navigator.mediaDevices.getUserMedia({
      video: deviceId.value ? { deviceId: { exact: deviceId.value } } : true,
      audio: false,
    })
    if (disposed) {
      result.getTracks().forEach((t) => t.stop())
      return
    }
    stream.value = result
    await nextTick()
    video.value.srcObject = result
    devices.value = (await navigator.mediaDevices.enumerateDevices()).filter(
      (d) => d.kind === 'videoinput',
    )
  } catch {
    ElMessage.warning('无法开启摄像头，请检查授权或上传图片')
  } finally {
    busy.value = false
  }
}
function capture() {
  if (!stream.value || !video.value.videoWidth) return
  canvas.value.width = video.value.videoWidth
  canvas.value.height = video.value.videoHeight
  canvas.value.getContext('2d').drawImage(video.value, 0, 0)
  hasPhoto.value = true
}
async function uploadFile(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 5 * 1024 * 1024) {
    ElMessage.warning('请选择5MB以内的JPEG或PNG图片')
    return
  }
  const bitmap = await createImageBitmap(file)
  if (disposed) {
    bitmap.close()
    return
  }
  // 上传前限制图片最长边，降低车牌识别请求体与网络耗时；后端仍独立校验类型和大小。
  const ratio = Math.min(1, 1600 / Math.max(bitmap.width, bitmap.height))
  canvas.value.width = bitmap.width * ratio
  canvas.value.height = bitmap.height * ratio
  canvas.value.getContext('2d').drawImage(bitmap, 0, 0, canvas.value.width, canvas.value.height)
  bitmap.close()
  hasPhoto.value = true
}
async function submit() {
  if (busy.value || !props.parkId || !hasPhoto.value) return
  busy.value = true
  try {
    const blob = await new Promise((resolve) => canvas.value.toBlob(resolve, 'image/jpeg', 0.85))
    const data = new FormData()
    data.append('file', blob, 'plate.jpg')
    data.append('park_id', props.parkId)
    const result = await request.post(
      props.mode === 'enter' ? '/enter/upload' : '/orders/upload',
      data,
    )
    plate.value = result.data
    hasPhoto.value = false
    ElMessage.success(props.mode === 'enter' ? '入场完成' : '离场完成')
  } catch {
  } finally {
    busy.value = false
  }
}
// 关闭媒体轨道，避免切换页面后摄像头仍被占用。
onBeforeUnmount(() => {
  disposed = true
  close()
})
</script>
<template>
  <section class="surface gate-lane">
    <header>
      <h2>{{ mode === 'enter' ? '车辆入场' : '车辆离场' }}</h2>
      <el-tag :type="mode === 'enter' ? 'success' : 'warning'">{{
        mode === 'enter' ? 'ENTRY' : 'EXIT'
      }}</el-tag>
    </header>
    <video ref="video" autoplay muted playsinline class="gate-video" />
    <div class="gate-controls">
      <el-select v-if="devices.length" v-model="deviceId" placeholder="摄像头" :disabled="!!stream"
        ><el-option
          v-for="d in devices"
          :key="d.deviceId"
          :value="d.deviceId"
          :label="d.label || '摄像头'" /></el-select
      ><el-button @click="camera" :disabled="busy || !enabled">{{
        stream ? '关闭摄像头' : '开启摄像头'
      }}</el-button
      ><el-button @click="capture" :disabled="!stream || busy">拍照</el-button
      ><el-button @click="input.click()" :disabled="busy || !enabled">选择图片</el-button>
    </div>
    <input
      ref="input"
      type="file"
      accept="image/jpeg,image/png"
      style="display: none"
      @change="uploadFile"
    /><canvas ref="canvas" class="gate-photo" v-show="hasPhoto" />
    <div class="gate-result" v-if="plate">
      最近处理车牌：<strong>{{ plate }}</strong>
    </div>
    <el-button
      type="primary"
      :disabled="!enabled || !parkId || !hasPhoto"
      :loading="busy"
      @click="submit"
      >识别并确认{{ mode === 'enter' ? '入场' : '离场' }}</el-button
    >
  </section>
</template>
<style scoped>
.gate-lane header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}
.gate-lane h2 {
  font-size: 18px;
  margin: 0;
}
.gate-video {
  background: #111e34;
  width: 100%;
  height: 230px;
  object-fit: contain;
  border-radius: 8px;
}
.gate-controls {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin: 16px 0;
}
.gate-controls .el-button {
  margin: 0;
}
.gate-controls .el-select {
  width: 100%;
}
.gate-photo {
  width: 100%;
  max-height: 200px;
  object-fit: contain;
  background: #f1f5f9;
  border-radius: 8px;
  margin: 0 0 16px;
}
.gate-result {
  font-size: 13px;
  margin: 16px 0;
}
</style>
