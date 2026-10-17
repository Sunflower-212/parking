<script setup>
import { User, Lock } from '@element-plus/icons-vue'
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
//控制注册与登录表单的显示， 默认显示注册
const isRegister = ref(false)
const form = ref()
const rememberMe = ref(false)
//定义数据模型
const registerDate=ref({
    username:'',
    password:'',
    repassword:''
})
const loginDate=ref({
    username:'',
    password:''
})
const idRadio = ref('1')
//校验密码
const checkRePassword = (rule,value,callback)=>{
    if(value===''){
        callback(new Error('请确认密码'))
    }else if(value!==registerDate.value.password){
        callback(new Error('请确保两次密码一致'))
    }else{
        callback()
    }
}
//定义表单校验规则
const rules={
    username:[
        { required: true, message: '请输入用户名', trigger: 'blur' },
        { min: 3, max: 16, message: '长度为3-16位', trigger: 'blur' }
    ],
    password:[
        { required: true, message: '请输入密码', trigger: 'blur' },
        { min: 3, max: 16, message: '长度为3-16位', trigger: 'blur' }
    ],
    repassword:[{ validator: checkRePassword, trigger: 'blur' }]
}
//调用后台接口完成注册
import {userRegisterService,loginService} from '@/api/user.js';
const register=async()=>{
    if(!await form.value.validate().catch(()=>false)) return;
    let result = await userRegisterService(registerDate.value);
    /* if(result.code===0){
        //成功了
        alert('注册成功');
    }else{
        //失败了
        alert('注册失败:'+result.message);
    } */

    //alert('注册成功');
    ElMessage.success('注册成功')
}
import { useTokenStore } from '@/stores/token.js';
import {useRouter} from 'vue-router'
import { jwtDecode } from "jwt-decode";
const tokenStore = useTokenStore();
const router = useRouter();
const login=async()=>{
    if(!await form.value.validate().catch(()=>false)) return;
    let result = await loginService(loginDate.value,idRadio.value);
    /* if(result.code===0){
        //成功了
        alert('登录成功');
    }else{
        //失败了
        alert('登录失败:'+result.message);
    } */
    //alert('登录成功');
    ElMessage.success('登录成功')
    //把得到的token存储到pinia中
    tokenStore.setToken(result.data, rememberMe.value);

    //跳转到首页，根据身份
    //解析token
    const token=result.data;
    const decoded = jwtDecode(token);

    if(idRadio.value === '1'&& decoded.claims.permissions===-1){
        router.push('/user')
    }
    if(idRadio.value === '2'){
        router.push('/admin')
        /* if(decoded.claims.permissions===0){
            router.push('/admin')
        }
        if(decoded.claims.permissions===1){
            router.push('/root')
        } */
    }

}
const caslogin=()=>ElMessage.info('统一身份登录尚未接入，请使用账号密码登录')
//定义函数清空数据模型的数据
const clearData=()=>{
    registerDate.value={
        username:'',
        password:'',
        repassword:''
    }
    loginDate.value={
        username:'',
        password:''
    }
}

</script>

<template>
    <el-row class="login-page">
        <el-col :span="12" class="bg"></el-col>
        <el-col :span="6" :offset="3" class="form">
            <!-- 注册表单 -->
            <el-form ref="form" size="large" autocomplete="off" v-if="isRegister" :model="registerDate" :rules="rules">
                <el-form-item>
                    <h1>注册</h1>
                </el-form-item>
                <el-form-item prop="username">
                    <el-input :prefix-icon="User" placeholder="请输入用户名" v-model="registerDate.username"></el-input>
                </el-form-item>
                <el-form-item prop="password">
                    <el-input :prefix-icon="Lock" type="password" placeholder="请输入密码" v-model="registerDate.password" show-password></el-input>
                </el-form-item>
                <el-form-item prop="repassword">
                    <el-input :prefix-icon="Lock" type="password" placeholder="请输入再次密码" v-model="registerDate.repassword" show-password></el-input>
                </el-form-item>
                <!-- 注册按钮 -->
                <el-form-item>
                    <el-button class="button" type="primary" auto-insert-space @click="register">
                        注册
                    </el-button>
                </el-form-item>
                <el-form-item class="flex">
                    <el-link type="info" :underline="false" @click="isRegister = false;clearData()">
                        ← 返回
                    </el-link>
                </el-form-item>
            </el-form>
            <!-- 登录表单 -->
            <el-form ref="form" size="large" autocomplete="off" v-else :model="loginDate" :rules="rules">
                <el-form-item>
                    <h1>登录</h1>
                </el-form-item>
                <el-form-item prop="username">
                    <el-input :prefix-icon="User" placeholder="请输入用户名" v-model="loginDate.username"></el-input>
                </el-form-item>
                <el-form-item prop="password">
                    <el-input name="password" :prefix-icon="Lock" type="password" placeholder="请输入密码" v-model="loginDate.password" show-password></el-input>
                </el-form-item>
                <el-radio-group v-model="idRadio" class="ml-4">
                    <el-radio value="1" size="large">用户</el-radio>
                    <el-radio value="2" size="large">管理员</el-radio>
                </el-radio-group>
                <el-form-item class="flex">
                    <div class="flex">
                        <el-checkbox v-model="rememberMe">记住我</el-checkbox>
                        <el-link type="primary" :underline="false" @click="ElMessage.info('请联系管理员重置密码')">忘记密码？</el-link>
                    </div>
                </el-form-item>
                <!-- 登录按钮 -->
                <el-form-item>
                    <el-button class="button" type="primary" auto-insert-space @click="login">登录</el-button>
                    <el-button class="button" type="primary" auto-insert-space @click="caslogin">统一身份登录</el-button>
                </el-form-item>
                <el-form-item class="flex">
                    <el-link type="info" :underline="false" @click="isRegister = true;clearData()">
                        注册 →
                    </el-link>
                </el-form-item>
            </el-form>
        </el-col>
    </el-row>
</template>

<style lang="scss" scoped>
/* 样式 */
.login-page {
    height: 100vh;
    background-color: #fff;

    .bg {
        background: url('@/assets/logo2.png') no-repeat 60% center / 240px auto,
            url('@/assets/login_bg.jpg') no-repeat center / cover;
        border-radius: 0 20px 20px 0;
    }

    .form {
        display: flex;
        flex-direction: column;
        justify-content: center;
        user-select: none;

        .title {
            margin: 0 auto;
        }

        .button {
            width: 100%;
        }

        .flex {
            width: 100%;
            display: flex;
            justify-content: space-between;
        }
    }
}
</style>
