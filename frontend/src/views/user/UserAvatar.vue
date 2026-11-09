<script setup>
import { ref, onMounted } from 'vue'
import { feedback as ElMessage, localFailure } from '@/utils/request-error-notice'
import request from '@/utils/request'
import useUser from '@/stores/userInfo'
import avatar from '@/assets/default.png'
import PageShell from '@/components/PageShell.vue'
const store = useUser(),
  image = ref(store.info.user_pic),
  enabled = ref(false),
  busy = ref(false),
  file = ref(),
  input = ref()
onMounted(async () => {
  try {
    enabled.value = (await request.get('/system/capabilities')).data.oss
  } catch {}
})
function select(e) {
  const chosen = e.target.files?.[0]
  if (!chosen) return
  if (!['image/jpeg', 'image/png'].includes(chosen.type) || chosen.size > 5 * 1024 * 1024) {
    ElMessage.warning('请选择不超过5MB的JPEG或PNG图片')
    e.target.value = ''
    return
  }
  file.value = chosen
}
async function upload() {
  if (!file.value || busy.value) return
  busy.value = true
  try {
    const data = new FormData()
    data.append('file', file.value)
    const result = await request.post('/user/upload', data)
    await request.patch('/user/updateAvatar', new URLSearchParams({ avatarUrl: result.data }))
    image.value = result.data
    store.setInfo({ ...store.info, user_pic: result.data })
    file.value = null
    ElMessage.success('头像已更新')
  } catch (error) {
    localFailure(error, '操作未完成，请重试')
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <PageShell title="更换头像"
    ><section class="surface">
      <el-alert
        v-if="!enabled"
        title="头像云存储尚未启用，请联系管理员配置 OSS。"
        type="info"
        :closable="false"
      /><img
        :src="image || avatar"
        alt="当前头像"
        style="
          width: 144px;
          height: 144px;
          object-fit: cover;
          border-radius: 24px;
          display: block;
          margin: 24px 0;
        "
      /><input
        ref="input"
        type="file"
        accept="image/jpeg,image/png"
        style="display: none"
        @change="select"
      />
      <p class="muted">{{ file?.name || '尚未选择新图片' }}</p>
      <el-button :disabled="!enabled || busy" @click="input.click()">选择图片</el-button
      ><el-button type="primary" :disabled="!file || !enabled" :loading="busy" @click="upload"
        >上传并保存</el-button
      >
    </section></PageShell
  >
</template>
