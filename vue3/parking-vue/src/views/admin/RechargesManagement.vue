<script lang="ts" setup>
import {ref} from 'vue'
//输入的金额
const amount = ref('')
//查询日期
const selectDate = ref('')
const recharges = ref([
    {
        id:'',
        user_name: '',
        amounts:'',
        pay_method:'',
        pay_no:'',
        flag:'',
        pay_time:'',
        create_time: ''
    }
])
interface Recharges {
    id:string,
    user_name: string,
    amounts:string,
    pay_method:string,
    pay_no:string,
    flag:string,
    create_time: string
}

//分页条数据模型
const pageNum = ref(1)//当前页
const total=ref(20)//总条数
const pageSize=ref(5)//每页条数

//当每页条数发生变化，调用此函数
const onSizeChange = (size)=>{
    pageSize.value=size
    rechargesPageList();
}

//当页码发送变化
const onCurrentChange = (num)=>{
    pageNum.value=num;
    rechargesPageList();
}

import {adminGetRechargesPageListService } from '@/api/recharges.js'
const rechargesPageList=async()=>{
    let params={
        pageNum:pageNum.value?pageNum.value:null,
        pageSize:pageSize.value?pageSize.value:null,
        amounts:amount.value?amount.value:null,
        date:selectDate.value?selectDate.value:null
    }
    let result = await adminGetRechargesPageListService(params);

    //渲染视图
    recharges.value=result.data.items;
    total.value = result.data.total;
}
rechargesPageList();

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


const payMethodFilterTag = (value: string, row: Recharges) => {
  return row.pay_method === value
}
const payFlagdFilterTag = (value:string,row:Recharges)=>{
    return row.flag === value
}
//清除筛选
import type { TableInstance } from 'element-plus'
const tableRef = ref<TableInstance>()
const clearFilter=()=>{
    tableRef.value!.clearFilter()
}

</script>

<template>
    <el-card class="page-container">
        <template #header>
            <div class="header">
                <span>充值管理</span>
            </div>
        </template>
        <!-- 搜索表单 -->
        <el-form  inline>
            <el-form-item label="充值金额:">
                <el-input v-model="amount" placeholder="请输入充值金额" style="width:250px"/>
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
                <el-button type="primary" @click="rechargesPageList">搜索</el-button>
                <el-button @click="amount='',selectDate='',rechargesPageList(),clearFilter()">重置</el-button>
            </el-form-item>
        </el-form>
        <el-table ref="tableRef" :data="recharges" height="450" stripe style="width: 100%">
            <el-table-column prop="id" label="充值编号" width="100" />
            <el-table-column prop="user_name" label="账号" width="100" />
            <el-table-column prop="amounts" label="充值金额" width="100" />
            <el-table-column
                prop="pay_method"
                label="支付方式"
                width="150"
                :filters="[
                    { text: '支付宝', value: 0 },
                    { text: '微信', value: 1 }
                ]"
                :filter-method="payMethodFilterTag"
                filter-placement="bottom-end"
            >
                <template #default="scope">
                    <el-tag
                        :type="scope.row.pay_method === 0 ?'primary': (scope.row.pay_method === 1?'success':'warning') "
                        disable-transitions
                    >{{ scope.row.pay_method === 0?'支付宝':(scope.row.pay_method === 1?'微信':'错误')}}</el-tag>
                </template>
            </el-table-column>
            <el-table-column prop="pay_no" label="支付订单号" width="200" />
            <el-table-column
                prop="flag"
                label="支付状态"
                width="150"
                :filters="[
                    { text: '未支付', value: 0 },
                    { text: '已支付', value: 1 },
                    { text: '取消支付', value: 2}
                ]"
                :filter-method="payFlagdFilterTag"
                filter-placement="bottom-end"
            >
                <template #default="scope">
                    <el-tag
                        :type="scope.row.flag === 0 ?'primary': (scope.row.flag === 1?'success':(scope.row.flag===2?'info':'danger')) "
                        disable-transitions
                    >{{ scope.row.flag === 0?'未支付':(scope.row.flag === 1?'已支付':(scope.row.flag===2?'取消支付':"错误"))}}</el-tag>
                </template>
            </el-table-column>
            <el-table-column prop="pay_time" label="支付时间" width="180" :formatter="formatDateTime"/>
            <el-table-column prop="create_time" label="创建时间" width="180" :formatter="formatDateTime"/>
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
