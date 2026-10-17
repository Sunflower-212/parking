<script lang="ts" setup>
import {ref} from 'vue'
import useAdminInfoStore from '@/stores/adminInfo';
const adminInfoStore = useAdminInfoStore();
const permissions = adminInfoStore.info.permissions;
//停车场name
const parkingName = ref('')
//查询日期
const selectDate = ref('')
//查询用户名称
const user_name = ref('')
//查询管理员名称
const admin_name = ref('')

const parking=ref([
    {
        'id':'',
        'park_name':"",
    }
])

const orders = ref([
    {
        id:'',
        user_name:'',
        plate_num: '',
        park_name:'',
        admin_name:'',
        enter_time: '',
        exit_time: '',
        parking_duration:'',
        moneys:''
    }
])

//分页条数据模型
const pageNum = ref(1)//当前页
const total=ref(20)//总条数
const pageSize=ref(5)//每页条数

//当每页条数发生变化，调用此函数
const onSizeChange = (size)=>{
    pageSize.value=size
    ordersPageList();
}

//当页码发送变化
const onCurrentChange = (num)=>{
    pageNum.value=num;
    ordersPageList();
}
import {adminOrdersListService} from '@/api/orders.js'
import {adminParkingListService,parkingListService} from '@/api/parking.js'
import { ElMessage } from 'element-plus';
//条件获取订单列表
const ordersPageList = async()=>{
    let params = {
        pageNum:pageNum.value,
        pageSize:pageSize.value,
        user_name:user_name.value?user_name.value:null,
        park_name:parkingName.value?parkingName.value:null,
        admin_name:admin_name.value?admin_name.value:null,
        date:selectDate.value?selectDate.value:null,
    }
    if(permissions===0||permissions===1){
        let result = await adminOrdersListService(params);
        //渲染视图
        orders.value=result.data.items;
        total.value = result.data.total;
    }
    else {
        ElMessage({type:'warning', message:'系统异常：非管理员'})
    }
}

//回显管理员管理停车场列表
const paringList = async()=>{
    if(permissions===0){
        let result = await adminParkingListService();
        parking.value = result.data;

    }
    else if(permissions===1){
        let result = await parkingListService();
        parking.value = result.data;

    }
    else {
        ElMessage({type:'warning', message:'系统异常：非管理员'})
    }

}
paringList();
ordersPageList();

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

const shortcuts = [
  {
    text: '今天',
    value: new Date(),
  },
  {
    text: '昨天',
    value: () => {
      const date = new Date()
      date.setTime(date.getTime() - 3600 * 1000 * 24)
      return date
    },
  },
  {
    text: '一周前',
    value: () => {
      const date = new Date()
      date.setTime(date.getTime() - 3600 * 1000 * 24 * 7)
      return date
    },
  },
]
//不可选择日期
const disabledDate = (time: Date) => {
  return time.getTime() > Date.now()
}
</script>

<template>
    <el-card class="page-container">
        <template #header>
            <div class="header">
                <span>订单管理</span>
            </div>
        </template>
        <!-- 搜索表单 -->
        <el-form  inline>
            <el-form-item label="停车场名称:">
                <el-select v-model="parkingName" placeholder="请选择停车场" style="width:150px">
                    <el-option
                        v-for="p in parking"
                        :key="p.id"
                        :label="p.park_name"
                        :value="p.park_name">
                    </el-option>
                </el-select>
            </el-form-item>
            <el-form-item label="用户名称:">
                <el-input v-model="user_name" placeholder="请输入用户名" style="width:120px"></el-input>
            </el-form-item>
            <el-form-item label="管理员名称:" v-if="permissions===1">
                <el-input v-model="admin_name" placeholder="请输入管理员名" style="width:120px"></el-input>
            </el-form-item>
            <el-form-item label="查询日期">
                <el-date-picker
                    v-model="selectDate"
                    type="date"
                    placeholder="选择查询日期"
                    value-format="YYYY-MM-DD"
                    :disabled-date="disabledDate"
                    :shortcuts="shortcuts"
                    size='default'
                    style="width:150px"
                />
            </el-form-item>
            <el-form-item>
                <el-button type="primary" @click="ordersPageList">搜索</el-button>
                <el-button @click="parkingName='',selectDate='',user_name='',admin_name='',ordersPageList()">重置</el-button>
            </el-form-item>
        </el-form>
        <el-table :data="orders" height="420" stripe style="width: 100%">
            <el-table-column prop="id" label="订单编号" width="90" />
            <el-table-column prop="user_name" label="用户名" width="100" />
            <el-table-column prop="plate_num" label="车牌号" width="120" />
            <el-table-column prop="park_name" label="停车场名称" width="200" />
            <el-table-column prop="admin_name" label="管理员名" width="100" v-if="permissions===1"/>
            <el-table-column prop="enter_time" label="入场时间" width="180" :formatter="formatDateTime"/>
            <el-table-column prop="exit_time" label="离场时间" width="180" :formatter="formatDateTime"/>
            <el-table-column prop="parking_duration" label="停车时长(分钟)" width="180" />
            <el-table-column prop="moneys" label="收费金额(元)" />
            <template #empty>
                <el-empty description="没有数据" />
            </template>
        </el-table>
        <!-- 分页条 -->
        <el-pagination v-model:current-page="pageNum" v-model:page-size="pageSize" :page-sizes="[5,10,20]"
            layout="jumper, total, sizes, prev, pager, next" background :total="total" @size-change="onSizeChange"
            @current-change="onCurrentChange" style="margin-top:10px;justify-content: flex-end;"/>
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
