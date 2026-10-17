<script lang="ts" setup>
import { ref } from 'vue'

//用户搜索时选中的停车场name
const parkingName = ref('')
const parking=ref([
    {
        'id':'',
        'park_name':"",

    }
])
//用户搜索时选中的起始时间
const startTime=ref('')
//用户搜索时选中的截止时间
const endTime=ref('')
const orders = ref([
    {
        id:'',
        plate_num: '',
        park_name:'',
        enter_time: '',
        exit_time: '',
        parking_duration:'',
        moneys:''
    }
])
import {userOrdersListService} from '@/api/orders.js'
import {parkingListService} from '@/api/parking.js'

//条件获取订单列表
const ordersList = async()=>{
    let params = {
        park_name:parkingName.value?parkingName.value:null,
        start_time:startTime.value?startTime.value:null,
        end_time:endTime.value?endTime.value:null
    }
    let result = await userOrdersListService(params);

    //渲染视图
    orders.value=result.data;
}

//回显停车场列表
const paringList = async()=>{
    let result = await parkingListService();
    parking.value = result.data;
}

paringList();
ordersList();

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

//不可选择日期
const disabledDate = (time: Date) => {
  return time.getTime() > Date.now()
}
</script>

<template>
    <el-card class="page-container">
        <template #header>
            <div class="header">
                <span>历史订单</span>
            </div>
        </template>
        <!-- 搜索表单 -->
        <el-form  inline>
            <el-form-item label="停车场名称:">
                <el-select v-model="parkingName" placeholder="请选择停车场" style="width:250px">
                    <el-option
                        v-for="p in parking"
                        :key="p.id"
                        :label="p.park_name"
                        :value="p.park_name">
                    </el-option>
                </el-select>
            </el-form-item>
            <el-form-item>
                <el-date-picker
                    v-model="startTime"
                    type="datetime"
                    placeholder="请选择订单起始时间"
                    value-format="YYYY-MM-DDTHH:mm:ss"
                />
            </el-form-item>
            <el-form-item>
                <div v-if="startTime===''&&endTime===''">-</div>
                <div v-else-if="startTime===''&&endTime!==''"><-</div>
                <div v-else-if="startTime!==''&&endTime===''">-></div>
                <div v-else><-></div>
            </el-form-item>
            <el-form-item>
                <el-date-picker
                    v-model="endTime"
                    type="datetime"
                    placeholder="请选择订单截止时间"
                    value-format="YYYY-MM-DDTHH:mm:ss"
                    :disabled-datetime="disabledDate"
                />
            </el-form-item>
            <el-form-item>
                <el-button type="primary" @click="ordersList">搜索</el-button>
                <el-button @click="parkingName='',startTime='',endTime='',ordersList()">重置</el-button>
            </el-form-item>
        </el-form>
        <el-table :data="orders" height="450" stripe style="width: 100%">
            <el-table-column prop="id" label="订单编号" width="150" />
            <el-table-column prop="plate_num" label="车牌号" width="120" />
            <el-table-column prop="park_name" label="停车场名称" width="200" />
            <el-table-column prop="enter_time" label="入场时间" width="180" :formatter="formatDateTime"/>
            <el-table-column prop="exit_time" label="离场时间" width="180" :formatter="formatDateTime"/>
            <el-table-column prop="parking_duration" label="停车时长(分钟)" width="180" />
            <el-table-column prop="moneys" label="收费金额(元)" />
            <template #empty>
                <el-empty description="没有数据" />
            </template>
        </el-table>
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
