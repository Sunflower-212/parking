<script lang='ts' setup>
import{
    Edit,Delete
    } from '@element-plus/icons-vue'
import {ref} from 'vue'
import useAdminInfoStore from '@/stores/adminInfo';
const adminInfoStore = useAdminInfoStore();
const permissions = adminInfoStore.info.permissions;
const dialogVisible = ref(false)
const selectParkingName = ref('')
const selectParkingAddress = ref('')
const selectAdminName = ref('')
const selectRuleName = ref('')
const title = ref('')
const billingRules = ref([
    {
        'id':'',
        'rule_name':'',
    }
])

const admins = ref([
    {
        'id':'',
        'admin_name':'',
    }
])
const parkings = ref([
    {
        id:'',
        admin_id:'',
        admin_name:'',
        park_name:'',
        park_address:'',
        park_mount:'',
        park_spare:'',
        park_storey:'',
        rule_id:'',
        rule_name:'',
        create_time:''
    }
])
const ParkingModel = ref(
    {
        id:'',
        admin_id:'',
        park_name:'',
        park_address:'',
        park_mount:'',
        park_spare:'',
        park_storey:'',
        rule_id:''
    }
)

const rules = {
    park_name:[
        { required: true, message: '请输入停车场名', trigger: 'blur' },
        { min: 1, max: 20, message: '长度为1-20位', trigger: 'blur' }
    ],
    park_address:[
        { required: true, message: '请输入停车场地址', trigger: 'blur' },
        { min: 1, max: 40, message: '长度为1-40位', trigger: 'blur' }
    ],
    park_mount:[
        { required: true, message: '请输入总车位数', trigger: 'blur' },
        { pattern: /^[1-9]\d*$/, message: '总车位数必须是一个不以0开头的正整数', trigger: 'blur' }
    ],
    park_spare:[
        { required: true, message: '请输入剩余车位数', trigger: 'blur' },
        { pattern: /^[1-9]\d*$/, message: '剩余车位数必须是一个不以0开头的正整数', trigger: 'blur' }
    ],
    park_storey:[
        { required: true, message: '请输入楼层数', trigger: 'blur' },
        { pattern: /^[1-9]\d*$/, message: '楼层数必须是一个不以0开头的正整数', trigger: 'blur' }
    ]
}

//分页条数据模型
const pageNum = ref(1)//当前页
const total=ref(20)//总条数
const pageSize=ref(5)//每页条数

import { adminGetParkingListService, rootGetParkingListService, } from '@/api/parking';
import { ElMessage,ElMessageBox } from 'element-plus';
const parkingList=async()=>{
    let params = {
            pageNum:pageNum.value,
            pageSize:pageSize.value,
            park_name:selectParkingName.value?selectParkingName.value:null,
            park_address:selectParkingAddress.value?selectParkingAddress.value:null,
            rule_name:selectRuleName.value?selectRuleName.value:null,
            admin_name:selectAdminName.value?selectAdminName.value:null
        }
    if(permissions===0){
        let result = await adminGetParkingListService(params);
        parkings.value = result.data.items
        total.value=result.data.total
    }else if(permissions===1){
        let result = await rootGetParkingListService(params);
        parkings.value = result.data.items
        total.value=result.data.total
    }else{
        ElMessage.error("系统异常，非管理员");
    }

}
parkingList();
import { ruleListService } from '@/api/rule';
//回显规则列表
const ruleList = async()=>{
    let result = await ruleListService(null)
    billingRules.value = result.data
}
ruleList();

//回显管理员列表
import { adminListService } from '@/api/admin';
const adminList = async()=>{
    let result = await adminListService()
    admins.value = result.data
}
adminList();
//当每页条数发生变化，调用此函数
const onSizeChange = (size)=>{
    pageSize.value=size
    parkingList();
}

//当页码发送变化
const onCurrentChange = (num)=>{
    pageNum.value=num;
    parkingList();
}

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
const addparkingBtn = ()=>{
    ParkingModel.value.id = ''
    ParkingModel.value.admin_id = ''
    ParkingModel.value.park_name=''
    ParkingModel.value.park_address=''
    ParkingModel.value.park_mount = ''
    ParkingModel.value.park_spare = ''
    ParkingModel.value.park_storey = ''
    ParkingModel.value.rule_id = ''
    title.value='添加停车场'
    dialogVisible.value=true
}

const updatePark = (row)=>{
    ParkingModel.value.id = row.id
    ParkingModel.value.admin_id = row.admin_id
    ParkingModel.value.park_name=row.park_name
    ParkingModel.value.park_address=row.park_address
    ParkingModel.value.park_mount = row.park_mount
    ParkingModel.value.park_spare = row.park_spare
    ParkingModel.value.park_storey = row.park_storey
    ParkingModel.value.rule_id = row.rule_id
    title.value='修改停车场'
    dialogVisible.value=true
}

import { adminParkingAddService,rootParkingAddService } from '@/api/parking';
const parkingAdd=async()=>{
    if(permissions===0){
        let result = await adminParkingAddService(ParkingModel.value)
        ElMessage.success('添加停车场'+ParkingModel.value.park_name+'成功')
        dialogVisible.value=false
        parkingList()
    }
    else if(permissions===1){
        let result = await rootParkingAddService(ParkingModel.value)
        ElMessage.success('添加停车场'+ParkingModel.value.park_name+'成功')
        dialogVisible.value=false
        parkingList()
    }else{
        ElMessage.error("系统异常，非管理员");
    }
}

import { adminParkingUpdateService,rootParkingUpdateService,deleteParkingService } from '@/api/parking';
const parkingUpdate=async()=>{
    if(permissions===0){
        let result = await adminParkingUpdateService(ParkingModel.value)
        ElMessage.success('修改停车场成功')
        dialogVisible.value=false
        parkingList()
    }else if(permissions===1){
        let result = await rootParkingUpdateService(ParkingModel.value)
        ElMessage.success('修改停车场成功')
        dialogVisible.value=false
        parkingList()
    }else{
        ElMessage.error("系统异常，非管理员");
    }
}


const deletePark=async(row)=>{
    try {
        // 确认用户是否真的要删除
        await ElMessageBox.confirm(
            '你确定要删除停车场: ' + row.park_name + ' 吗?',
            '温馨提示',
            {
                confirmButtonText: '确认',
                cancelButtonText: '取消',
                type: 'warning'
            }
        );
        // 用户点击了“确认”，现在尝试删除停车场
        try {
            let result = await deleteParkingService(row.park_name); // 假设这是调用后端的函数
            ElMessage({
                type:'success',
                message:'删除停车场成功'
            })
            //刷新列表
            parkingList();
        } catch (error) {
            //在响应拦截其中已给出提示
        }
    } catch (error) {
        ElMessage({
            type:'info',
            message:'取消删除'
        })
    }
}
</script>
<template>
    <el-card class="page-container">
        <template #header>
            <div class="header">
                <span>停车场管理</span>
                <div class="extra">
                    <el-button type="primary" @click="addparkingBtn">新增停车场</el-button>
                </div>
            </div>
        </template>
        <!-- 搜索表单 -->
        <el-form  inline >
            <el-form-item label="停车场名称:">
                <el-input v-model="selectParkingName" placeholder="请输入停车场名" style="width:140px"></el-input>
            </el-form-item>
            <el-form-item label="停车场地址:">
                <el-input v-model="selectParkingAddress" placeholder="请输入停车场名" style="width:140px"></el-input>
            </el-form-item>
            <el-form-item label="管理员名:" v-if="permissions===1">
                <el-input v-model="selectAdminName" placeholder="请输入管理员名" style="width:140px"></el-input>
            </el-form-item>
            <el-form-item label="规则名:">
                <el-input v-model="selectRuleName" placeholder="请输入规则名" style="width:140px"></el-input>
            </el-form-item>
            <el-form-item >
                <el-button type="primary" @click="parkingList">搜索</el-button>
                <el-button @click="selectRuleName='',selectParkingAddress='',selectParkingName='',selectAdminName='',parkingList()">重置</el-button>
            </el-form-item>
        </el-form>
        <el-table :data="parkings" height="420" stripe style="width: 100%">
            <el-table-column prop="id" label="id" width="50" />
            <el-table-column prop="park_name" label="停车场" width="150" />
            <el-table-column prop="admin_name" label="管理员" width="90" />
            <el-table-column prop="park_address" label="地址" width="250" />
            <el-table-column prop="park_mount" label="总车位" width="68" />
            <el-table-column prop="park_spare" label="剩余车位" width="85" />
            <el-table-column prop="park_storey" label="层数" width="70" />
            <el-table-column prop="rule_name" label="规则名" width="210" />
            <el-table-column prop="create_time" label="创建时间" width="150" :formatter="formatDateTime" />
            <el-table-column label="操作" width="100">
                <template #default="{ row }">
                    <el-button :icon="Edit" circle plain type="primary" @click="updatePark(row)"></el-button>
                    <el-button :icon="Delete" circle plain type="danger" @click="deletePark(row)"></el-button>
                </template>
            </el-table-column>
            <template #empty>
                <el-empty description="没有数据" />
            </template>
            <!-- 分页条 -->
            <el-pagination v-model:current-page="pageNum" v-model:page-size="pageSize" :page-sizes="[5,10,20]"
                layout="jumper, total, sizes, prev, pager, next" background :total="total" @size-change="onSizeChange"
                @current-change="onCurrentChange" style="margin-top:10px;justify-content: flex-end;"/>
        </el-table>
        <!-- 添加停车场弹窗 -->
        <el-dialog v-model="dialogVisible" :title="title" width="30%" @close="dialogVisible=false">
            <el-form :model="ParkingModel" :rules="rules" label-width="120px" style="padding-right: 30px">
                <el-form-item label="停车场名" prop="park_name">
                    <el-input v-model="ParkingModel.park_name"></el-input>
                </el-form-item>
                <el-form-item label="管理员名" v-if="permissions===1">
                    <el-select v-model="ParkingModel.admin_id" placeholder="请选择管理员">
                        <el-option
                            v-for="a in admins"
                            :key="a.id"
                            :label="a.admin_name"
                            :value="a.id">
                        </el-option>
                    </el-select>
                </el-form-item>
                <el-form-item label="停车场地址" prop="park_address">
                    <el-input  v-model="ParkingModel.park_address"></el-input>
                </el-form-item>
                <el-form-item label="总车位" prop="park_mount">
                    <el-input type="number" v-model="ParkingModel.park_mount"></el-input>
                </el-form-item>
                <el-form-item label="剩余车位" prop="park_spare">
                    <el-input type="number" v-model="ParkingModel.park_spare"></el-input>
                </el-form-item>
                <el-form-item label="楼层数" prop="park_storey">
                    <el-input type="number" v-model="ParkingModel.park_storey"></el-input>
                </el-form-item>
                <el-form-item label="规则名">
                    <el-select v-model="ParkingModel.rule_id" placeholder="请选择计费规则">
                        <el-option
                            v-for="r in billingRules"
                            :key="r.id"
                            :label="r.rule_name"
                            :value="r.id">
                        </el-option>
                    </el-select>
                </el-form-item>
            </el-form>
            <template #footer>
                <span class="dialog-footer">
                    <el-button @click="dialogVisible=false">取消</el-button>
                    <el-button type="primary" @click="title=='添加停车场'?parkingAdd():parkingUpdate()"> 确认 </el-button>
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
