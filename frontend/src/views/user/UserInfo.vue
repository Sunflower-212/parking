<script setup>
import { ref } from 'vue'
import { feedback as ElMessage, localFailure } from '@/utils/request-error-notice'
import request from '@/utils/request'
import useUser from '@/stores/userInfo'
import PageShell from '@/components/PageShell.vue'
import LicensePlateInput from '@/components/LicensePlateInput.vue'
import { validPlate } from '@/utils/license-plate'
const plateInput = ref()
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
    {
      validator: (_, value, done) =>
        validPlate(value) ? done() : done(new Error('请选择完整且符合类型的车牌号')),
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
  if (busy.value) return
  const plateValid = plateInput.value.validate()
  if (!(await form.value.validate().catch(() => false)) || !plateValid) return
  busy.value = true
  try {
    await request.put('/user/update', model.value)
    try {
      store.setInfo((await request.get('/user/userInfo', { feedback: false })).data)
      ElMessage.success('资料已更新')
    } catch {
      ElMessage.warning('资料已更新，账户刷新失败，请刷新页面')
    }
  } catch (error) {
    localFailure(error, '操作未完成，请重试')
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <PageShell title="基本资料"
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
          ><el-form-item prop="plate_num" style="grid-column: 1 / -1">
            <LicensePlateInput
              ref="plateInput"
              v-model="model.plate_num"
              @update:model-value="form?.clearValidate('plate_num')"
              :disabled="busy" /></el-form-item
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
