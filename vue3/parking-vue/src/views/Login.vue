<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { feedback as ElMessage, localFailure } from '@/utils/request-error-notice'
import { useTokenStore } from '@/stores/token'
import { consumePaymentReturn } from '@/utils/payment-context'
import { loginService, userRegisterService } from '@/api/user'
const router = useRouter(),
  token = useTokenStore(),
  registering = ref(false),
  busy = ref(false),
  remember = ref(false),
  role = ref('1'),
  form = ref()
const data = ref({ username: '', password: '', repassword: '' })
const rules = {
  username: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { min: 3, max: 16, message: '账号长度为3至16位', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 3, max: 16, message: '密码长度为3至16位', trigger: 'blur' },
  ],
  repassword: [
    {
      validator: (_, v, cb) => (v === data.value.password ? cb() : cb(new Error('两次密码不一致'))),
      trigger: 'blur',
    },
  ],
}
async function submit() {
  if (busy.value || !(await form.value.validate().catch(() => false))) return
  busy.value = true
  try {
    if (registering.value) {
      await userRegisterService(data.value)
      ElMessage.success('注册成功，请登录')
      registering.value = false
      data.value.password = ''
      data.value.repassword = ''
    } else {
      const result = await loginService(data.value, role.value)
      token.setToken(result.data, remember.value)
      ElMessage.success('登录成功')
      router.replace(role.value === '1' ? consumePaymentReturn() || '/user' : '/admin')
    }
  } catch (error) {
    localFailure(error, '操作未完成，请重试')
  } finally {
    busy.value = false
  }
}
function toggle() {
  registering.value = !registering.value
  data.value = { username: '', password: '', repassword: '' }
  form.value?.clearValidate()
}
</script>
<template>
  <div class="login-screen">
    <section class="login-intro">
      <router-link to="/" class="login-brand"><span class="brand-icon">P</span> 易停车</router-link>
      <div>
        <h1>让停车，<br />更简单一点。</h1>

        <div class="parking-visual" aria-hidden="true">
          <span v-for="i in 6" :key="i" :class="{ occupied: i === 2 || i === 5 }">{{
            i === 2 || i === 5 ? '▰' : 'P'
          }}</span>
        </div>
      </div>
    </section>
    <section class="login-panel">
      <div class="login-form">
        <h2>{{ registering ? '创建你的账号' : '欢迎回来' }}</h2>

        <el-form
          ref="form"
          :model="data"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="submit"
          ><el-form-item label="账号" prop="username"
            ><el-input
              v-model="data.username"
              name="username"
              aria-label="账号"
              placeholder="请输入账号"
              autocomplete="username" /></el-form-item
          ><el-form-item label="密码" prop="password"
            ><el-input
              v-model="data.password"
              name="password"
              aria-label="密码"
              placeholder="请输入密码"
              type="password"
              show-password
              :autocomplete="registering ? 'new-password' : 'current-password'" /></el-form-item
          ><el-form-item v-if="registering" label="确认密码" prop="repassword"
            ><el-input
              v-model="data.repassword"
              placeholder="请再次输入密码"
              type="password"
              show-password
              autocomplete="new-password" /></el-form-item
          ><el-radio-group v-if="!registering" v-model="role" aria-label="登录身份"
            ><el-radio-button value="1">用户登录</el-radio-button
            ><el-radio-button value="2">管理员登录</el-radio-button></el-radio-group
          >
          <div v-if="!registering" class="login-options">
            <el-checkbox v-model="remember">记住登录状态</el-checkbox
            ><el-button link @click="ElMessage.info('请联系超级管理员重置密码')"
              >忘记密码？</el-button
            >
          </div>
          <el-button type="primary" native-type="submit" class="login-submit" :loading="busy">{{
            registering ? '注册账号' : '登 录'
          }}</el-button></el-form
        >
        <div class="login-switch">
          {{ registering ? '已有账号？' : '还没有账号？' }}
          <el-button link type="primary" @click="toggle">{{
            registering ? '返回登录' : '立即注册'
          }}</el-button>
        </div>
        <el-button
          v-if="!registering"
          link
          class="sso-link"
          @click="ElMessage.info('统一身份登录尚未接入，请使用账号密码登录')"
          >统一身份登录</el-button
        >
      </div>
    </section>
  </div>
</template>
<style scoped>
.login-screen {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.05fr 1fr;
}
.login-intro {
  padding: 44px 60px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  color: #fff;
  background: radial-gradient(ellipse at 85% 85%, #244fa4 0%, transparent 55%), #101d35;
  min-height: 100vh;
}
.login-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 24px;
  font-weight: 650;
}
.login-intro h1 {
  font-size: clamp(38px, 4.3vw, 66px);
  line-height: 1.35;
  letter-spacing: -2px;
  margin: 20px 0;
}
.login-intro p {
  color: #a4b7d3;
  line-height: 2;
}
.parking-visual {
  display: grid;
  grid-template-columns: repeat(3, 70px);
  gap: 10px;
  margin-top: 42px;
  transform: rotate(-8deg);
  width: max-content;
}
.parking-visual span {
  height: 76px;
  display: grid;
  place-items: center;
  border: 1px solid #6d96d54d;
  border-radius: 8px;
  color: #588de2;
  background: #ffffff05;
  font-size: 26px;
}
.parking-visual .occupied {
  background: #2563eb;
  color: white;
  border-color: #2563eb;
}
.login-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  padding: 40px;
  background: #fff;
  position: relative;
}
.login-form {
  max-width: 380px;
  width: 100%;
}
.login-form h2 {
  font-size: 32px;
  margin: 16px 0 10px;
  letter-spacing: -1px;
}
.login-form > p {
  font-size: 13px;
  line-height: 1.8;
  margin-bottom: 32px;
}
.login-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 16px 0;
}
.login-submit {
  width: 100%;
  margin-top: 12px;
  height: 46px;
}
.login-switch {
  font-size: 13px;
  text-align: center;
  margin: 24px 0 8px;
  color: #8190a5;
}
.sso-link {
  display: block;
  margin: auto;
  font-size: 12px;
}
@media (max-width: 767px) {
  .login-screen {
    grid-template-columns: 1fr;
  }
  .login-intro {
    display: none;
  }
  .login-panel {
    padding: 36px 24px;
    min-height: 100vh;
  }
  .login-form h2 {
    font-size: 30px;
  }
}
</style>
