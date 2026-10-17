<script lang="ts" setup>
import{
    Edit,Delete
    } from '@element-plus/icons-vue'
import {ref} from 'vue'
import useAdminInfoStore from '@/stores/adminInfo';
const adminInfoStore = useAdminInfoStore();
const adminName = adminInfoStore.info.admin_name;
const selectAdminName = ref('')
const selectPermission = ref('')
const dialogVisible = ref(false)
const admins=ref([])
const permissions = ([
    {
        'permission':'0',
        'name':'停车场管理员'
    },
    {
        'permission':'1',
        'name':'超级管理员'
    }
])

const adminModel=ref({
    admin_name:'',
    permissions:''
})

const rules = {
    admin_name:[
        { required: true, message: '请输入管理名', trigger: 'blur' },
        { min: 3, max: 16, message: '长度为3-16位', trigger: 'blur' }
    ],
}
//分页条数据模型
const pageNum = ref(1)//当前页
const total=ref(20)//总条数
const pageSize=ref(5)//每页条数


//当每页条数发生变化，调用此函数
const onSizeChange = (size)=>{
    pageSize.value=size
    adminPageList();
}

//当页码发送变化
const onCurrentChange = (num)=>{
    pageNum.value=num;
    adminPageList();
}

import { adminPageListService } from '@/api/admin';
const adminPageList=async()=>{
    let params = {
        pageNum:pageNum.value,
        pageSize:pageSize.value,
        admin_name:selectAdminName.value?selectAdminName.value:null,
        permissions:selectPermission.value?selectPermission.value:null
    }
    let result = await adminPageListService(params);
    admins.value = result.data.items;
    total.value=result.data.total;
}

adminPageList();


const formatDateTime=(row, column, cellValue)=>{
    if (cellValue) {
        const date = new Date(cellValue);
        // 使用 toLocaleString 方法来格式化日期时间，不显示 T
        return date.toLocaleString('zh-CN', {
          year: 'numeric',
          month: '2-digit',
          day: '2-digit',
          hour: '2-digit',
          minute: '2-digit',
          second: '2-digit',
        });
      }
      return ''; // 如果 cellValue 为空或未定义，返回空字符串

}

import { ElMessageBox,ElMessage } from 'element-plus';
import { rootResetPasswordService } from '@/api/admin';
const resetPassword = async(row)=>{
    try{
        await ElMessageBox.confirm(
            '你确定要重置管理员: '+row.admin_name+' 的密码吗?',
            '温馨提示',
            {
                confirmButtonText:'确认',
                cancelButtonText:'取消',
                type:'warning'
            }
        );
        try{
            //重置密码
            //调用后端重置密码接口
            let params = {
                admin_name:row.admin_name
            }
            await rootResetPasswordService(params);
            ElMessage({
                type:'success',
                message:'重置密码成功'
            })
        }catch (error){
            //在响应拦截其中已给出提示
        }
    }catch(error){
        ElMessage({
            type:'info',
            message:'取消重置密码'
        })
    }
}

import { rootDeleteAdminService } from '@/api/admin';
const deleteAdmin=async(row)=>{
    try{
        await ElMessageBox.confirm(
            '你确定要删除管理员: '+row.admin_name+' 吗?',
            '温馨提示',
            {
                confirmButtonText:'确认',
                cancelButtonText:'取消',
                type:'warning'
            }
        );
        try{
            //删除用户
            await rootDeleteAdminService(row.admin_name);
            ElMessage({
                type:'success',
                message:'删除用户成功'
            })
            //刷新列表
            adminPageList();
        }catch (error){
            //在响应拦截其中已给出提示
        }
    }catch(error){
        ElMessage({
            type:'info',
            message:'取消删除'
        })
    }
}
const addAdmin=()=>{
    adminModel.value.admin_name=null;
    adminModel.value.permissions=null;
    dialogVisible.value = true
}

//添加管理员
import { adminAddService } from '@/api/admin';
const adminAdd = async()=>{{
    await adminAddService(adminModel.value);
    ElMessage.success('添加管理员:'+adminModel.value.admin_name+' 成功!')
    //隐藏弹窗
    dialogVisible.value = false
    //再次访问后台接口，查询所有管理员
    adminPageList();
}}

</script>


<template>
    <el-card class="page-container">
        <template #header>
            <div class="header">
                <span>用户管理</span>
                <div class="extra">
                    <el-button type="primary" @click="addAdmin">新增管理员</el-button>
                </div>
            </div>
        </template>
        <!-- 搜索表单 -->
        <el-form  inline >
            <el-form-item label="管理员名称:">
                <el-input v-model="selectAdminName" placeholder="请输入管理员名" style="width:140px"></el-input>
            </el-form-item>
            <el-form-item label="身份:">
                <el-select v-model="selectPermission" placeholder="请选择身份" style="width:120px">
                    <el-option
                        v-for="p in permissions"
                        :key="p.permission"
                        :label="p.name"
                        :value="p.permission">
                    </el-option>
                </el-select>
            </el-form-item>
            <el-form-item>
                <el-button type="primary" @click="adminPageList">搜索</el-button>
                <el-button @click="selectAdminName='',selectPermission='',adminPageList()">重置</el-button>
            </el-form-item>
        </el-form>
        <el-table :data="admins" height="420" stripe style="width: 100%">
            <el-table-column prop="id" label="管理员ID" width="90" />
            <el-table-column prop="admin_name" label="管理员名" width="110" />
            <el-table-column prop="permissions" label="身份" width="150" >
                <template #default="scope">
                    <el-tag
                        :type="scope.row.permissions === 0 ?'success': (scope.row.permissions === 1 ?'danger':'warning') "
                        disable-transitions="true" effect="dark"
                    >{{ scope.row.permissions === 0?'停车场管理员':(scope.row.permissions === 1?'超级管理员':"未知身份")}}</el-tag>
                </template>
            </el-table-column>
            <el-table-column prop="create_time" label="注册时间" width="180" :formatter="formatDateTime"/>
            <el-table-column label="操作" width="100">
                <template #default="{ row }">
                    <el-button circle plain type="primary" @click="resetPassword(row)"><font-awesome-icon :icon="['fas','lock']" ></font-awesome-icon></el-button>
                    <el-button :icon="Delete" circle plain type="danger" :disabled="row.admin_name===adminName" @click="deleteAdmin(row)"></el-button>
                </template>
            </el-table-column>
            <template #empty>
                <el-empty description="没有数据" />
            </template>
        </el-table>
        <!-- 分页条 -->
        <el-pagination v-model:current-page="pageNum" v-model:page-size="pageSize" :page-sizes="[5,10,20]"
            layout="jumper, total, sizes, prev, pager, next" background :total="total" @size-change="onSizeChange"
            @current-change="onCurrentChange" style="margin-top:10px;justify-content: flex-end;"/>
        <!-- 添加用户弹窗 -->
        <el-dialog v-model="dialogVisible" title="新增管理员" width="30%" @close="dialogVisible=false">
            <el-form :model="adminModel" :rules="rules" label-width="100px" style="padding-right: 30px">
                <el-form-item label="管理名" prop="admin_name">
                    <el-input v-model="adminModel.admin_name"></el-input>
                </el-form-item>
                <el-form-item label="身份" >
                    <el-select v-model="adminModel.permissions" placeholder="请选择身份" style="width:120px">
                    <el-option
                        v-for="p in permissions"
                        :key="p.permission"
                        :label="p.name"
                        :value="p.permission">
                    </el-option>
                </el-select>
                </el-form-item>
            </el-form>
            <template #footer>
                <span class="dialog-footer">
                    <el-button @click="dialogVisible=false">取消</el-button>
                    <el-button type="primary" @click="adminAdd"> 确认 </el-button>
                </span>
            </template>
        </el-dialog>
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
