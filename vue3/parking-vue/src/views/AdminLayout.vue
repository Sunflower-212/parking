<script setup>
import {
    Memo,
    ShoppingCart,
    User,
    SwitchButton,
    CaretBottom
} from '@element-plus/icons-vue'
import avatar from '@/assets/default.png'


import {onBeforeMount, ref} from 'vue'
import  useAdminInfoStore  from '@/stores/adminInfo.js'
import { adminInfoService } from '@/api/admin.js';
import { useTokenStore } from '@/stores/token';
const tokenStore = useTokenStore();
const adminInfoStore = useAdminInfoStore();
const loadingInfo = ref(true);
//获取管理员详细信息
const getAdminInfo = async()=>{
    //调用接口
    let result = await adminInfoService();

    //存储数据
    adminInfoStore.setInfo(result.data);
    loadingInfo.value = false;
}
onBeforeMount(()=>{
    getAdminInfo();
})


//退出登陆的接口
import {adminLogoutService} from '@/api/admin.js'
const adminLogout = async()=>{
    await adminLogoutService();
}

//条目被点击后调用的函数
import {useRouter} from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus';

const router = useRouter();
const handleCommand = (command)=>{
    //判断指令
    if(command==="logout"){
        ElMessageBox.confirm(
            '你确定要退出登陆吗',
            '温馨提示',
            {
                confirmButtonText:'确认',
                cancelButtonText:'取消',
                type:'warning'
            }
        )
        .then(async()=>{
            //退出登录
            //调用后端登出接口
            await adminLogout();
            //1、清楚pinia中存储的token和userInfo
            tokenStore.removeToken();
            adminInfoStore.removeInfo();
            //跳转到登陆页面
            router.push('/login')
            ElMessage({
                type:'success',
                message:'退出登陆成功'
            })

        })
        .catch(()=>{
            ElMessage({
                type:'info',
                message:'取消登出'
            })
        })
    }else{
        //路由
        router.push('/admin/'+command)
    }
}
</script>


<template>
    <el-container class="layout-container">
        <!-- 左侧菜单 -->
        <el-aside width="200px">
            <div class="el-aside__logo"></div>
            <el-menu active-text-color="#ffd04b" background-color="#232323"  text-color="#fff"
                router>
                <el-menu-item index="/admin/entAndExitManagement">
                    <font-awesome-icon :icon="['fas','sign-in']" style="padding: 0px 10px 0px 7px"></font-awesome-icon>
                    <span>出入口管理</span>
                </el-menu-item>
                <el-menu-item index="/admin/parkManagement">
                    <font-awesome-icon :icon="['fas','square-parking']" style="padding: 0px 10px 0px 8px"></font-awesome-icon>
                    <span>停车场管理</span>
                </el-menu-item>
                <el-menu-item index="/admin/ruleManagement">
                    <font-awesome-icon :icon="['fas','dollar-sign']" style="padding: 0px 11px 0px 9px"></font-awesome-icon>
                    <span>计费规则管理</span>
                </el-menu-item>
                <el-menu-item index="/admin/spaceManagement">
                    <font-awesome-icon :icon="['fas','car']" style="padding: 0px 9px 0px 6px">
                    </font-awesome-icon>
                    <span>车位管理</span>
                </el-menu-item>
                <el-menu-item index="/admin/orderManagement">
                    <el-icon>
                        <ShoppingCart />
                    </el-icon>
                    <span>订单管理</span>
                </el-menu-item>
                <el-menu-item index="/admin/rechargesManagement">
                    <el-icon>
                        <Memo />
                    </el-icon>
                    <span>充值管理</span>
                </el-menu-item>
                <el-menu-item index="/admin/userManagement">
                    <el-icon>
                        <User />
                    </el-icon>
                    <span>用户管理</span>
                </el-menu-item>
                <el-menu-item index="/admin/adminManagement" v-if="adminInfoStore.info.permissions===1">
                    <font-awesome-icon :icon="['fas','user-tie']" style="padding: 0px 10px 0px 6px"></font-awesome-icon>
                    <span>管理员管理</span>
                </el-menu-item>
                <el-menu-item index="/admin/charts">
                    <font-awesome-icon :icon="['fas','user-tie']" style="padding: 0px 10px 0px 6px"></font-awesome-icon>
                    <span>图表统计</span>
                </el-menu-item>
            </el-menu>
        </el-aside>
        <!-- 右侧主区域 -->
        <el-container>
            <!-- 头部区域 -->
            <el-header>
                <div v-if="adminInfoStore.info.permissions===0">欢迎管理员：<strong>{{adminInfoStore.info.admin_name}}</strong></div>
                <div v-if="adminInfoStore.info.permissions===1">欢迎超级管理员：<strong>{{adminInfoStore.info.admin_name}}</strong></div>
                <!-- 下拉菜单 -->
                <el-dropdown placement="bottom-end" @command="handleCommand">
                    <span class="el-dropdown__box" >
                        <el-tag type="danger" size="small" round effect="dark" v-if="adminInfoStore.info.permissions===0"
                        :style="{ padding: '2px 6px', fontSize: '10px', lineHeight: '12px' }">管</el-tag>
                        <el-tag type="danger" size="small" round effect="dark" v-if="adminInfoStore.info.permissions===1"
                        :style="{ padding: '2px 6px', fontSize: '10px', lineHeight: '12px' }">超</el-tag>
                        <el-avatar :src="adminInfoStore.info.user_pic?adminInfoStore.info.user_pic:avatar" />
                        <el-icon>
                            <CaretBottom />
                        </el-icon>
                    </span>
                    <template #dropdown>
                        <el-dropdown-menu>
                            <el-dropdown-item command="logout" :icon="SwitchButton">退出登录</el-dropdown-item>
                        </el-dropdown-menu>
                    </template>
                </el-dropdown>
            </el-header>
            <!-- 中间区域 -->
            <el-main>
                <router-view v-if="!loadingInfo"></router-view>
            </el-main>
            <!-- 底部区域 -->
            <el-footer>停车场管理系统 ©2024 Created by zjm</el-footer>
        </el-container>
    </el-container>
</template>
<style lang="scss" scoped>
.layout-container {
    height: 100vh;

    .el-aside {
        background-color: #232323;

        &__logo {
            height: 120px;
            background: url('@/assets/logo.png') no-repeat center / 120px auto;
        }

        .el-menu {
            border-right: none;
        }
    }

    .el-header {
        background-color: #fff;
        display: flex;
        align-items: center;
        justify-content: space-between;

        .el-dropdown__box {
            display: flex;
            align-items: flex-end;
            .el-icon {
                color: #999;
                margin-left: 10px;
            }

            &:active,
            &:focus {
                outline: none;
            }
        }
    }

    .el-footer {
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 14px;
        color: #666;
    }
}
</style>
