<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { feedback as ElMessage, localFailure } from '@/utils/request-error-notice'
import request from '@/utils/request'
import { useTokenStore } from '@/stores/token'
import useUser from '@/stores/userInfo'
import useAdmin from '@/stores/adminInfo'
import PageShell from '@/components/PageShell.vue'
const isAdmin = useRoute().path.startsWith('/admin')
const admin = useAdmin()
const router = useRouter(),
  token = useTokenStore(),
  user = useUser(),
  model = ref({ old_pwd: '', new_pwd: '', re_pwd: '' }),
  form = ref(),
  busy = ref(false)
const rules = {
  old_pwd: [{ required: true, message: '请输入原密码' }],
  new_pwd: [
    { required: true, message: '请输入新密码' },
    { pattern: /^\S{3,16}$/, message: '密码为3至16位非空白字符' },
    {
      validator: (_, v, cb) =>
        v !== model.value.old_pwd ? cb() : cb(new Error('新密码不能与原密码相同')),
    },
  ],
  re_pwd: [
    { required: true, message: '请确认密码' },
    {
      validator: (_, v, cb) => (v === model.value.new_pwd ? cb() : cb(new Error('两次密码不一致'))),
    },
  ],
}
async function save() {
  if (busy.value || !(await form.value.validate().catch(() => false))) return
  busy.value = true
  try {
    await request.patch(isAdmin ? '/admin/updatePassword' : '/user/updatePassword', model.value)
    token.removeToken()
    user.removeInfo()
    admin.removeInfo()
    ElMessage.success('密码已修改，请重新登录')
    router.replace('/login')
  } catch (error) {
    localFailure(error, '操作未完成，请重试')
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <PageShell title="修改密码"
    ><section class="surface">
      <el-form
        ref="form"
        :model="model"
        :rules="rules"
        label-position="top"
        style="max-width: 420px"
        @submit.prevent="save"
        ><el-form-item
          v-for="(label, key) in { old_pwd: '原密码', new_pwd: '新密码', re_pwd: '确认新密码' }"
          :key="key"
          :label="label"
          :prop="key"
          ><el-input
            v-model="model[key]"
            type="password"
            show-password
            :autocomplete="key === 'old_pwd' ? 'current-password' : 'new-password'"
            maxlength="16" /></el-form-item
        ><el-button type="primary" native-type="submit" :loading="busy"
          >修改密码</el-button
        ></el-form
      >
    </section></PageShell
  >
</template>
