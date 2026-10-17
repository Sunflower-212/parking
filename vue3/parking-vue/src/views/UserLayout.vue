<script setup>
import {
    Memo,
    ShoppingCart,
    MapLocation,
    UserFilled,
    User,
    Money,
    Crop,
    EditPen,
    SwitchButton,
    CaretBottom
} from '@element-plus/icons-vue'
import avatar from '@/assets/default.png'

import { userInfoService } from '@/api/user.js';
import  useUserInfoStore  from '@/stores/userInfo.js'
import { useTokenStore } from '@/stores/token';
const tokenStore = useTokenStore();
const userInfoStore = useUserInfoStore();

//调用函数获取用户详细信息
const getUserInfo = async()=>{
    //调用接口
    let result = await userInfoService();

    //数据存储到pinia中
    userInfoStore.setInfo(result.data);
}
getUserInfo();

//退出登录的接口
import {userLogoutService} from '@/api/user.js'
const userLogout= async()=>{
    await userLogoutService();
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
            await userLogout();
            //1、清楚pinia中存储的token和userInfo
            tokenStore.removeToken();
            userInfoStore.removeInfo();
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
        router.push('/user/'+command)
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
                <el-menu-item index="/user/spaceReservation">
                    <el-icon>
                        <MapLocation />
                    </el-icon>
                    <span>车位预约</span>
                </el-menu-item>
                <el-menu-item index="/user/enterHistory">
                    <font-awesome-icon :icon="['fas','sign-in']" style="padding: 0px 10px 0px 7px"></font-awesome-icon>
                    <span>入场历史</span>
                </el-menu-item>
                <el-menu-item index="/user/orderHistory">
                    <el-icon>
                        <ShoppingCart />
                    </el-icon>
                    <span>订单记录</span>
                </el-menu-item>
                <el-menu-item index="/user/rechargeHistory">
                    <el-icon>
                        <Memo />
                    </el-icon>
                    <span>充值记录</span>
                </el-menu-item>

                <el-sub-menu index="user">
                    <template #title>
                        <el-icon>
                            <UserFilled />
                        </el-icon>
                        <span>个人中心</span>
                    </template>
                    <el-menu-item index="/user/info">
                        <el-icon>
                            <User />
                        </el-icon>
                        <span>基本资料</span>
                    </el-menu-item>
                    <el-menu-item index="/user/account">
                        <el-icon>
                            <Money />
                        </el-icon>
                        <span>个人账户</span>
                    </el-menu-item>
                    <el-menu-item index="/user/avatar">
                        <el-icon>
                            <Crop />
                        </el-icon>
                        <span>更换头像</span>
                    </el-menu-item>
                    <el-menu-item index="/user/resetPassword">
                        <el-icon>
                            <EditPen />
                        </el-icon>
                        <span>修改密码</span>
                    </el-menu-item>
                </el-sub-menu>
            </el-menu>
        </el-aside>
        <!-- 右侧主区域 -->
        <el-container>
            <!-- 头部区域 -->
            <el-header>
                <div>欢迎用户：<strong>{{userInfoStore.info.name!==''?userInfoStore.info.name:userInfoStore.info.user_name}}</strong></div>
                <!-- 下拉菜单 -->
                <el-dropdown placement="bottom-end" @command="handleCommand">
                    <span class="el-dropdown__box" >
                        <el-tag type="danger" size="small" round effect="dark" v-if="userInfoStore.info.status===1"
                        :style="{ padding: '2px 6px', fontSize: '10px', lineHeight: '12px' }">V</el-tag>
                        <el-tag type="info" size="small" round effect="dark" v-if="userInfoStore.info.status===0"
                        :style="{ padding: '2px 6px', fontSize: '10px', lineHeight: '12px' }">V</el-tag>
                        <el-avatar :src="userInfoStore.info.user_pic?userInfoStore.info.user_pic:avatar" />
                        <el-icon>
                            <CaretBottom />
                        </el-icon>
                    </span>
                    <template #dropdown>
                        <el-dropdown-menu>
                            <el-dropdown-item command="info" :icon="User">基本资料</el-dropdown-item>
                            <el-dropdown-item command="account" :icon="Money">个人账户</el-dropdown-item>
                            <el-dropdown-item command="avatar" :icon="Crop">更换头像</el-dropdown-item>
                            <el-dropdown-item command="resetPassword" :icon="EditPen">重置密码</el-dropdown-item>
                            <el-dropdown-item command="logout" :icon="SwitchButton">退出登录</el-dropdown-item>
                        </el-dropdown-menu>
                    </template>
                </el-dropdown>
            </el-header>
            <!-- 中间区域 -->
            <el-main>
                <router-view></router-view>
            </el-main>
            <!-- 底部区域 -->
            <el-footer>停车场用户系统 ©2024 Created by zjm</el-footer>
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
