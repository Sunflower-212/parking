<script lang="ts" setup>
import {ref} from 'vue'
import type { TableInstance } from 'element-plus'
const tableRef = ref<TableInstance>();
const enters = ref([])
//停车场id
const parkingId = ref('')
//查询日期
const selectDate = ref('')
const parking=ref([
    {
        'id':'',
        'park_name':"",
    }
])

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

//分页条数据模型
const pageNum = ref(1)//当前页
const total=ref(20)//总条数
const pageSize=ref(5)//每页条数

//当每页条数发生变化，调用此函数
const onSizeChange = (size)=>{
    pageSize.value=size
    enterPageList();
}

//当页码发送变化
const onCurrentChange = (num)=>{
    pageNum.value=num;
    enterPageList();
}


import { userEnterPageListService } from '@/api/enter';
const enterPageList = async()=>{
    let params = {
        pageNum:pageNum.value,
        pageSize:pageSize.value,
        park_name:parking.value.find(p=>p.id===parkingId.value)?.park_name || null,
        date:selectDate.value?selectDate.value:null,
    }
    let result = await userEnterPageListService(params);
    enters.value=result.data.items
    total.value = result.data.total;
}
import {parkingListService} from '@/api/parking.js'
//停车场列表
const paringList = async()=>{
    let result = await parkingListService();
    parking.value = result.data;
}
paringList();
enterPageList();
//不可选择日期
const disabledDate = (time: Date) => {
  return time.getTime() > Date.now()
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
interface Enters{
    id:string,
    plate_num:string,
    user_id:string,
    user_name:string,
    park_id:string,
    park_name:string,
    enter_time:string,
    flag:string,
    reservation:string
}

const flagFilterTag = (value: string, row: Enters) => {
  return row.flag === value
}

const reservationFilterTag= (value: string, row: Enters) => {
  return row.reservation === value
}

//清除筛选
const clearFilter=()=>{
    tableRef.value!.clearFilter()
}
</script>

<template>
    <el-card class="page-container">
        <template #header>
            <div class="header">
                <span>入场历史</span>
            </div>
        </template>
        <!-- 搜索表单 -->
        <el-form  inline >
            <el-form-item label="停车场名称:">
                <el-select v-model="parkingId" placeholder="请选择停车场" style="width:150px">
                    <el-option
                        v-for="p in parking"
                        :key="p.id"
                        :label="p.park_name"
                        :value="p.park_name">
                    </el-option>
                </el-select>
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
                <el-button type="primary" @click="enterPageList">搜索</el-button>
                <el-button @click="parkingId='',selectDate='',enterPageList(),clearFilter()">重置</el-button>
            </el-form-item>
        </el-form>
        <el-table ref="tableRef" :data="enters" height="400" stripe style="width: 100%">
            <el-table-column prop="id" label="入场id" width="110"/>
                <el-table-column prop="park_name" label="停车场名称" width="180"/>
                <el-table-column prop="plate_num" label="车牌号" width="120"/>
                <el-table-column prop="user_name" label="用户名" width="100"/>
                <el-table-column prop="enter_time" label="入场时间" width="180" :formatter="formatDateTime"/>
                <el-table-column
                    prop="flag"
                    label="状态"
                    width="150"
                    :filters="[
                        { text: '未入场', value: 0 },
                        { text: '已入场', value: 1 },
                        { text: '已出场', value: 2 },
                    ]"
                    :filter-method="flagFilterTag"
                    filter-placement="bottom-end"
                >
                    <template #default="scope">
                        <el-tag
                            :type="scope.row.flag === 0 ?'primary': (scope.row.flag === 1?'success':'warning') "
                            disable-transitions
                        >{{ scope.row.flag === 0?'未入场':(scope.row.flag === 1?'已入场':'已出场')}}</el-tag>
                    </template>
                </el-table-column>
                <el-table-column
                    prop="reservation"
                    label="预约"
                    width="150"
                    :filters="[
                        { text: '未预约', value: 0 },
                        { text: '已预约', value: 1 },
                        { text: '取消预约', value: 2 },
                    ]"
                    :filter-method="reservationFilterTag"
                    filter-placement="bottom-end"
                >
                    <template #default="scope">
                        <el-tag
                            :type="scope.row.reservation === 0 ?'primary': (scope.row.reservation === 1?'success':'warning') "
                            disable-transitions
                        >{{ scope.row.reservation === 0?'未预约':(scope.row.reservation === 1?'已预约':'取消预约')}}</el-tag>
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
