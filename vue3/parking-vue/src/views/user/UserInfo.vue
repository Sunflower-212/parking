<script setup>
import { ref , computed} from 'vue'
import useUserInfoStore from '@/stores/userInfo.js'
const userInfoStore = useUserInfoStore();

const userInfo = ref({
    ...userInfoStore.info
})
const originalUserInfo = ref({})
const rules = {
    name: [
        { required: true, message: '请输入姓名', trigger: 'blur' },
        {
            pattern: /^\S{1,10}$/,
            message: '昵称必须是1-10位的非空字符串',
            trigger: 'blur'
        }
    ],
    plate_num: [
        { required: true, message: '请输入用户车牌', trigger: 'blur' },
        {
            pattern:/^([京津沪渝冀晋辽吉黑苏浙皖闽赣鲁豫鄂湘粤桂琼川贵云藏陕甘青宁新港澳台])[A-Z]{1}-[A-Z0-9]{5,6}$/,
            message:'车牌格式为XX-XXXXX(X)',
            trigger:'blur'
        }
    ],
    car_color:[
        { required:false,message:'请输入车辆颜色',trigger:'blur'},
        {
            pattern:/^[\u4e00-\u9fa5]{1,5}色$/,
            message:'颜色格式为xxxxx色',
            trigger:'blur'
        }
    ],
    tel_num:[
        { required:true,message:'请输入电话号码',trigger:'blur'},
        {
            pattern:/^1(3[0-9]|4[57]|5[0-35-9]|7[0678]|8[0-9])\d{8}$/,
            message:'请输入正确的电话格式',
            trigger:'blur'
        }
    ]
}
const saveOriginalUserInfo = () => {
    Object.assign(originalUserInfo.value, userInfo.value);
};
saveOriginalUserInfo();

// 检查表单数据是否已经改变的计算属性
const isFormChanged = computed(() => {
      return JSON.stringify(userInfo.value) !== JSON.stringify(originalUserInfo.value);
})

//修改个人信息
import { updateUserInfoService } from '@/api/user.js'
import {ElMessage} from 'element-plus'
const updateUserInfo =async()=>{
    let result = await updateUserInfoService(userInfo.value);
    ElMessage.success(result.message?result.message:'修改成功')
    //修改pinia中的个人信息
    userInfoStore.setInfo(userInfo.value)
    //重新保存修改后的数据
    saveOriginalUserInfo();
}
</script>



<template>
    <el-card class="page-container">
        <template #header>
            <div class="header">
                <span>基本资料</span>
            </div>
        </template>
        <el-row>
            <el-col :span="12">
                <el-form :model="userInfo" :rules="rules" label-width="100px" size="large">
                    <el-form-item label="登录名称">
                        <el-input v-model="userInfo.user_name" disabled></el-input>
                    </el-form-item>
                    <el-form-item label="用户姓名" prop="name">
                        <el-input v-model="userInfo.name"></el-input>
                    </el-form-item>
                    <el-form-item label="车牌号码" prop="plate_num">
                        <el-input v-model="userInfo.plate_num"></el-input>
                    </el-form-item>
                    <el-form-item label="车辆颜色" prop="car_color">
                        <el-input v-model="userInfo.car_color"></el-input>
                    </el-form-item>
                    <el-form-item label="电话号码" prop="tel_num">
                        <el-input v-model="userInfo.tel_num"></el-input>
                    </el-form-item>
                    <el-form-item>
                        <el-button type="primary" @click="updateUserInfo" :disabled="!isFormChanged">提交修改</el-button>
                    </el-form-item>
                </el-form>
            </el-col>
        </el-row>
    </el-card>
</template>


<style lang="scss" scoped>
.page-container {
    min-height: 100%;
    box-sizing: border-box;

    .header {
        display: flex;
        align-items: center;
        justify-content: space-between;
    }
}
</style>
