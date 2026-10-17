<script lang="ts" setup>
import {Delete} from '@element-plus/icons-vue'
import {ref} from 'vue'
const selectUserName = ref('')
const selectPlateNum = ref('')
const selectCarColor =ref('')
const selectStatus = ref('')
const dialogVisible = ref(false)

//弹窗标题
const title=ref('')

const userModel = ref({
    user_name:'',
    name:'',
    plate_num:'',
    car_color:'',
    tel_num:''
})

const rules = {
    user_name:[
        { required: true, message: '请输入用户名', trigger: 'blur' },
        { min: 3, max: 16, message: '长度为3-16位', trigger: 'blur' }
    ],
}

const users = ref([
    {
        user_name:'',
        name:'',
        plate_num:'',
        car_color:'',
        tel_num:'',
        balance:'',
        status:'',
        vip_end_time:'',
        create_time:''
    }
])

const vip = ([
    {
        'status':'0',
        'name':'普通用户'
    },
    {
        'status':'1',
        'name':'会员'
    }
])


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
//分页条数据模型
const pageNum = ref(1)//当前页
const total=ref(20)//总条数
const pageSize=ref(5)//每页条数

import { userPageListService,userAddService } from '@/api/user';
//查询用户列表
const userPageList=async()=>{
    let params = {
        pageNum:pageNum.value,
        pageSize:pageSize.value,
        user_name:selectUserName.value?selectUserName.value:null,
        plate_num:selectPlateNum.value?selectPlateNum.value:null,
        car_color:selectCarColor.value?selectCarColor.value:null,
        status:selectStatus.value?selectStatus.value:null,
    }
    let result = await userPageListService(params);
    users.value=result.data.items;
    total.value=result.data.total;

}
userPageList();

//当每页条数发生变化，调用此函数
const onSizeChange = (size)=>{
    pageSize.value=size
    userPageList();
}

//当页码发送变化
const onCurrentChange = (num)=>{
    pageNum.value=num;
    userPageList();
}

const addUserBtn = ()=>{
    title.value="添加用户";
    userModel.value.user_name='';
    userModel.value.name='',
    userModel.value.plate_num='';
    userModel.value.car_color='',
    userModel.value.tel_num=''
    dialogVisible.value=true;
}
import { ElMessage } from 'element-plus';
//添加用户
const userAdd = async()=>{{
    await userAddService(userModel.value);
    ElMessage.success('添加用户:'+userModel.value.user_name+' 成功!')
    //隐藏弹窗
    dialogVisible.value = false
    //再次访问后台接口，查询所有用户
    userPageList();
}}



import { ElMessageBox } from 'element-plus';
import { adminResetPasswordService } from '@/api/user';
const resetPassword = (row)=>{
    ElMessageBox.confirm(
        '你确定要重置用户: '+row.user_name+' 的密码吗?',
        '温馨提示',
        {
            confirmButtonText:'确认',
            cancelButtonText:'取消',
            type:'warning'
        }
    )
    .then(async()=>{
        //修改密码
        //调用后端修改密码接口
        let params = {
            user_name:row.user_name
        }
        await adminResetPasswordService(params);
        ElMessage({
            type:'success',
            message:'重置密码成功'
        })
    })
    .catch(()=>{
        ElMessage({
            type:'info',
            message:'取消重置密码'
        })
    })
}



import { adminDeleteUserService } from '@/api/user';
const deleteUser=(row)=>{
    ElMessageBox.confirm(
        '你确定要删除用户: '+row.user_name+' 吗?',
        '温馨提示',
        {
            confirmButtonText:'确认',
            cancelButtonText:'取消',
            type:'warning'
        }
    ).then(async()=>{
        //删除用户
        await adminDeleteUserService(row.user_name);
        ElMessage({
            type:'success',
            message:'删除用户成功'
        })
        //刷新列表
        userPageList();
    })
    .catch(()=>{
        ElMessage({
            type:'info',
            message:'取消删除'
        })
    })
}

</script>

<template>
    <el-card class="page-container">
        <template #header>
            <div class="header">
                <span>用户管理</span>
                <div class="extra">
                    <el-button type="primary" @click="addUserBtn">新增用户</el-button>
                </div>
            </div>
        </template>
        <!-- 搜索表单 -->
        <el-form  inline >
            <el-form-item label="用户名称:">
                <el-input v-model="selectUserName" placeholder="请输入用户名" style="width:140px"></el-input>
            </el-form-item>
            <el-form-item label="车牌号:" >
                <el-input v-model="selectPlateNum" placeholder="请输入车牌号" style="width:140px"></el-input>
            </el-form-item>
            <el-form-item label="车辆颜色:" >
                <el-input v-model="selectCarColor" placeholder="请输入车辆颜色" style="width:140px"></el-input>
            </el-form-item>
            <el-form-item label="身份:">
                <el-select v-model="selectStatus" placeholder="请选择身份" style="width:120px">
                    <el-option
                        v-for="vip in vip"
                        :key="vip.status"
                        :label="vip.name"
                        :value="vip.status">
                    </el-option>
                </el-select>
            </el-form-item>
            <el-form-item>
                <el-button type="primary" @click="userPageList">搜索</el-button>
                <el-button @click="selectUserName='',selectPlateNum='',selectCarColor='',selectStatus='',userPageList()">重置</el-button>
            </el-form-item>
        </el-form>
        <el-table :data="users" height="420" stripe style="width: 100%">
            <el-table-column prop="user_name" label="用户名" width="90" />
            <el-table-column prop="name" label="名字" width="90" />
            <el-table-column prop="plate_num" label="车牌号" width="110" />
            <el-table-column prop="car_color" label="车辆颜色" width="90" />
            <el-table-column prop="tel_num" label="电话" width="140" />
            <el-table-column prop="balance" label="余额" width="100" />
            <el-table-column prop="status" label="身份" width="100" >
                <template #default="scope">
                    <el-tag
                        :type="scope.row.status === 0 ?'success': (scope.row.status === 1 ?'danger':'warning') "
                        disable-transitions="true" effect="dark"
                    >{{ scope.row.status === 0?'普通用户':(scope.row.status === 1?'会员':"未知身份")}}</el-tag>
                </template>
            </el-table-column>
            <el-table-column prop="vip_end_time" label="会员截止时间" width="180" :formatter="formatDateTime"/>
            <el-table-column prop="create_time" label="注册时间" width="180" :formatter="formatDateTime"/>
            <el-table-column label="操作" width="100">
                <template #default="{ row }">
                    <el-button circle plain type="primary" @click="resetPassword(row)"><font-awesome-icon :icon="['fas','lock']" ></font-awesome-icon></el-button>
                    <el-button :icon="Delete" circle plain type="danger" :disabled="row.status===1||row.balance!==0" @click="deleteUser(row)"></el-button>
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
        <el-dialog v-model="dialogVisible" :title="title" width="30%" @close="dialogVisible=false">
            <el-form :model="userModel" :rules="rules" label-width="100px" style="padding-right: 30px">
                <el-form-item label="用户名" prop="user_name">
                    <el-input v-model="userModel.user_name"></el-input>
                </el-form-item>
                <el-form-item label="用户姓名" >
                    <el-input v-model="userModel.name" ></el-input>
                </el-form-item>
                <el-form-item label="车牌号码" >
                    <el-input v-model="userModel.plate_num" ></el-input>
                </el-form-item>
                <el-form-item label="车辆颜色" >
                    <el-input v-model="userModel.car_color" ></el-input>
                </el-form-item>
                <el-form-item label="电话号码" >
                    <el-input v-model="userModel.tel_num" ></el-input>
                </el-form-item>
            </el-form>
            <template #footer>
                <span class="dialog-footer">
                    <el-button @click="dialogVisible=false">取消</el-button>
                    <el-button type="primary" @click="userAdd"> 确认 </el-button>
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
