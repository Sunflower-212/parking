<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import useUser from '@/stores/userInfo'
import PageShell from '@/components/PageShell.vue'
const store = useUser(),
  model = ref({ ...store.info }),
  form = ref(),
  busy = ref(false)
const rules = {
  name: [
    { required: true, message: '请输入姓名' },
    { min: 1, max: 10, message: '姓名最多10位' },
  ],
  plate_num: [
    { required: true, message: '请输入车牌' },
    {
      pattern:
        /^[京津沪渝冀晋辽吉黑苏浙皖闽赣鲁豫鄂湘粤桂琼川贵云藏陕甘青宁新港澳台][A-Z]-[A-Z0-9]{5,6}$/,
      message: '例如 京A-12345',
    },
  ],
  car_color: [
    { required: true, message: '请输入车辆颜色' },
    { pattern: /^[\u4e00-\u9fa5]{1,5}色$/, message: '例如 白色' },
  ],
  tel_num: [
    { required: true, message: '请输入手机号' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式无效' },
  ],
}
async function save() {
  if (busy.value || !(await form.value.validate().catch(() => false))) return
  busy.value = true
  try {
    await request.put('/user/update', model.value)
    store.setInfo((await request.get('/user/userInfo')).data)
    ElMessage.success('资料已更新')
  } catch {
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <PageShell title="基本资料" description="完善有效车牌后即可预约车位"
    ><section class="surface">
      <el-form
        ref="form"
        :model="model"
        :rules="rules"
        label-position="top"
        style="max-width: 600px"
        @submit.prevent="save"
        ><div class="form-grid">
          <el-form-item label="用户名"
            ><el-input :model-value="model.user_name" disabled /></el-form-item
          ><el-form-item label="姓名" prop="name"
            ><el-input v-model="model.name" maxlength="10" /></el-form-item
          ><el-form-item label="车牌号" prop="plate_num"
            ><el-input
              v-model="model.plate_num"
              placeholder="京A-12345"
              maxlength="10" /></el-form-item
          ><el-form-item label="车辆颜色" prop="car_color"
            ><el-input v-model="model.car_color" placeholder="白色" maxlength="6" /></el-form-item
          ><el-form-item label="手机号" prop="tel_num"
            ><el-input v-model="model.tel_num" maxlength="11"
          /></el-form-item>
        </div>
        <el-button type="primary" native-type="submit" :loading="busy">保存资料</el-button></el-form
      >
    </section></PageShell
  >
</template>
