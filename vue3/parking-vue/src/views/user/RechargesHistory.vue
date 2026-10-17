<script script lang="ts" setup>
import {ref} from 'vue';
//用户输入的金额
const amount = ref('')
//用户搜索时选中的起始时间
const startTime=ref('')
//用户搜索时选中的截止时间
const endTime=ref('')
//
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
import {userRechargesListService} from '@/api/recharges.js'

const rechargesList =async()=>{
    let params = {
        amounts:amount.value?amount.value:null,
        start_time:startTime.value?startTime.value:null,
        end_time:endTime.value?endTime.value:null
    }
    let result = await userRechargesListService(params);
    //渲染视图
    recharges.value=result.data;

}
rechargesList();

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

const payMethodFilterTag = (value: string, row: Recharges) => {
  return row.pay_method === value
}
const payFlagdFilterTag = (value:string,row:Recharges)=>{
    return row.flag === value
}



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
                <span>充值记录</span>
            </div>
        </template>
        <!-- 搜索表单 -->
        <el-form  inline>
            <el-form-item label="充值金额:">
                <el-input v-model="amount" placeholder="请输入充值金额" style="width:250px"/>
            </el-form-item>

            <el-form-item >
                <el-date-picker
                    v-model="startTime"
                    type="datetime"
                    placeholder="请选择充值起始时间"
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
                    placeholder="请选择充值截止时间"
                    value-format="YYYY-MM-DDTHH:mm:ss"
                />
            </el-form-item>
            <el-form-item>
                <el-button type="primary" @click="rechargesList">搜索</el-button>
                <el-button @click="amount='',startTime='',endTime='',rechargesList(),clearFilter()">重置</el-button>
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
