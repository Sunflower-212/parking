<script lang="ts" setup>
import  useUserInfoStore  from '@/stores/userInfo.js'
import { useTokenStore } from '@/stores/token';
import {useRouter} from 'vue-router'
const tokenStore = useTokenStore();
const userInfoStore = useUserInfoStore();
const router = useRouter();
import {ref} from 'vue'
import { ElMessageBox,ElMessage } from 'element-plus';
const password = ref({
    old_pwd:'',
    new_pwd:'',
    re_pwd:''
})
//校验新密码
const checkNewPassword = (rule,value,callback)=>{
    if(value===password.value.old_pwd){
        callback(new Error('请确保新密码与旧密码不一致'))
    }else{
        callback()
    }
}
//校验密码
const checkRePassword = (rule,value,callback)=>{
    if(value!==password.value.new_pwd){
        callback(new Error('请确保两次密码一致'))
    }else{
        callback()
    }
}

const rules = {
    old_pwd:[
        {required: true, message: '请输入原密码', trigger: 'blur' },
        { min: 3, max: 16, message: '长度为3-16位', trigger: 'blur' }
    ],
    new_pwd:[
        {required: true, message: '请输入新密码', trigger: 'blur' },
        {validator: checkNewPassword, trigger: 'blur'},
        { min: 3, max: 16, message: '长度为3-16位', trigger: 'blur' }
    ],
    re_pwd:[
        {required: true, message: '请再次确认新密码', trigger: 'blur' },
        {validator: checkRePassword, trigger: 'blur'}
    ]
}

import { userUpdatePasswordService } from '@/api/user';
const updatePassword=async()=>{
    try{
        await ElMessageBox.confirm(
            '你确定要修改密码吗?',
            '温馨提示',
            {
                confirmButtonText:'确认',
                cancelButtonText:'取消',
                type:'warning'
            }
        );
        try{
            //修改密码
            //调用后端修改密码接口
            let params={
                old_pwd:password.value.old_pwd?password.value.old_pwd:null,
                new_pwd:password.value.new_pwd?password.value.new_pwd:null,
                re_pwd:password.value.re_pwd?password.value.re_pwd:null
            }
            let result = await userUpdatePasswordService(params)
            ElMessage({
                type:'success',
                message:'修改密码成功'
            })
            //1、清楚pinia中存储的token和userInfo
            tokenStore.removeToken();
            userInfoStore.removeInfo();
            //跳转到登陆页面
            router.push('/login')
        }catch (error) {
            //在响应拦截其中已给出提示
        }
    }catch(error){
        ElMessage({
            type:'info',
            message:'取消修改密码'
        })
    }
}

</script>






<template>
    <el-card class="page-container">
        <template #header>
            <div class="header">
                <span>修改密码</span>
            </div>
        </template>
        <el-row>
            <el-col :span="12">
                <el-form :model="password" :rules="rules" label-width="100px" size="large">
                    <el-form-item label="旧密码" prop="old_pwd">
                        <el-input type="password" v-model="password.old_pwd" show-password></el-input>
                    </el-form-item>
                    <el-form-item label="新密码" prop="new_pwd">
                        <el-input type="password" v-model="password.new_pwd" show-password></el-input>
                    </el-form-item>
                    <el-form-item label="确认新密码" prop="re_pwd">
                        <el-input type="password" v-model="password.re_pwd" show-password></el-input>
                    </el-form-item>
                    <el-form-item>
                        <el-button type="primary" @click="updatePassword">修改密码</el-button>
                    </el-form-item>
                </el-form>
            </el-col>
        </el-row>
    </el-card>
</template>
