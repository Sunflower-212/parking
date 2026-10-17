<script lang="ts" setup>
import { ref, onMounted, nextTick } from 'vue'
import useAdminInfoStore from '@/stores/adminInfo';
const adminInfoStore = useAdminInfoStore();

import { ElMessage } from 'element-plus';
import * as echarts from 'echarts';
const enterAndExitAllDataLineRef = ref(null)
const enterAndExitLineRef = ref(null)
const ordersAllDataLineRef = ref(null)
const ordersDataLineRef = ref(null)
const rechargesLineRef = ref(null)
let enterAndExitAllDataLineChart
let ordersAllDataLineChart
let enterAndExitLineChart
let ordersDataLineChart
let rechargesLineChart

const parkingId = ref('')
const parking = ref([
    {
        id: '',
        park_name: "",
    }
])

const methodId = ref(0)
const method = [
    {
        id: 0,
        method_name: "总体",
    },
    {
        id: 1,
        method_name: "局部",
    }
]
const userNumber = ref(null)
const todayNewUserNumber = ref(null)
const enterNumber = ref(null)
const exitNumber = ref(null)

const parkingNumber = ref(null)
const adminNumber = ref(null)

const enterAndExitAllDataLineOption = {
    title: {
        text: "近七天出入库总数据"
    },
    tooltip: {
        trigger: 'axis'
    },
    legend: {
        data: ['总入库', '总出库']
    },
    xAxis: {
        type: 'category',
        data: []
    },
    yAxis: {
        type: 'value'
    },
    series: [
        {
            name: '总入库',
            type: 'line',
            smooth: true,
            data: []
        },
        {
            name: '总出库',
            type: 'line',
            smooth: true,
            data: []
        },
    ]
};
const enterAndExitLineOption = {
    title: {
        text: "近七天出入库数据"
    },
    tooltip: {
        trigger: 'axis'
    },
    legend: {
        data: ['入库', '出库']
    },
    xAxis: {
        type: 'category',
        data: []
    },
    yAxis: {
        type: 'value'
    },
    series: [
        {
            name: '入库',
            type: 'line',
            smooth: true,
            data: []
        },
        {
            name: '出库',
            type: 'line',
            smooth: true,
            data: []
        },
    ]
};
const rechargesLineOption = {
    title: {
        text: "近七天充值数据"
    },
    tooltip: {
        trigger: 'axis'
    },
    legend: {
        data: ['充值金额', '充值次数']
    },
    xAxis: {
        type: 'category',
        data: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
    },
    yAxis: {
        type: 'value'
    },
    series: [
        {
            name: '充值金额',
            type: 'line',
            smooth: true,
            data: [120, 132, 101, 134, 90, 230, 210]
        },
        {
            name:'充值次数',
            type: 'bar',
            data: [120, 200, 150, 80, 70, 110, 130],
        }
    ]
};
const ordersAllDataLineOption = {
    title: {
        text: "近七天停车总收入数据"
    },
    tooltip: {
        trigger: 'axis'
    },
    xAxis: {
        type: 'category',
        data: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
    },
    yAxis: {
        type: 'value'
    },
    series: [
        {
            name: '总收入',
            type: 'line',
            stack: 'Total',
            smooth: true,
            data: [120, 132, 101, 134, 90, 230, 210]
        }
    ]
};
const ordersDataLineOption = {
    title: {
        text: "近七天停车收入数据"
    },
    tooltip: {
        trigger: 'axis'
    },
    xAxis: {
        type: 'category',
        data: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
    },
    yAxis: {
        type: 'value'
    },
    series: [
        {
            name: '收入',
            type: 'line',
            stack: 'Total',
            smooth: true,
            data: [120, 132, 101, 134, 90, 230, 210]
        }
    ]
};
import { adminParkingListService, parkingListService } from '@/api/parking';
const initialization = async () => {
    if (adminInfoStore.info.permissions === 0) {
        let result = await adminParkingListService();
        parking.value = result.data;
    }
    else if (adminInfoStore.info.permissions === 1) {
        let result = await parkingListService();
        parking.value = result.data;
        parkingNumber.value = parking.value.length
    }
    else {
        ElMessage({ type: 'warning', message: '系统异常：非管理员' })
    }
    if (parking.value.length === 0) {
        ElMessage.info("没有停车场，请先添加");
        return;
    }
    parkingId.value = parking.value[0].id
}
import { getUserDataService } from '@/api/user';
const getUserData = async () => {
    let result = await getUserDataService();
    userNumber.value = result.data.userNumber;
    todayNewUserNumber.value = result.data.todayNewUserNumber;
}
getUserData();
import { getEnterAllNumberService } from '@/api/enter';
const getEnterAllNumber = async () => {
    if (methodId.value !== 0) {
        ElMessage.error("非超级管理员，无法获取所有进场数量")
        return;
    }
    let result = await getEnterAllNumberService()
    enterNumber.value = result.data
}
import { getEnterNumberByParkingIdService } from '@/api/enter';
const getEnterNumberByParkingId = async () => {
    let params = {
        park_id: parkingId.value ? parkingId.value : null
    }
    let result = await getEnterNumberByParkingIdService(params)
    enterNumber.value = result.data
}

import { getExitAllNumberService, getExitNumberByParkingIdService } from '@/api/orders';
const getExitAllNumber = async () => {
    if (methodId.value !== 0) {
        ElMessage.error("非超级管理员，无法获取所有出场数量")
        return;
    }
    let result = await getExitAllNumberService();
    exitNumber.value = result.data
}

const getExitNumberByParkingId = async () => {
    let params = {
        park_id: parkingId.value ? parkingId.value : null
    }
    let result = await getExitNumberByParkingIdService(params)
    exitNumber.value = result.data
}



import { getAdminNumberService } from '@/api/admin';
const getAdminNumber = async () => {
    let result = await getAdminNumberService();
    adminNumber.value = result.data
}

import { getEnterAllDataService, getEnterDataService } from '@/api/enter';
import { getExitAllDataService, getExitDataService } from '@/api/orders';
const getEnterAndExitAllData = async () => {
    let result = await getEnterAllDataService();
    enterAndExitAllDataLineOption.xAxis.data = result.data?.map(v => v.date) || []
    enterAndExitAllDataLineOption.series[0].data = result.data?.map(v => v.number) || []

    result = await getExitAllDataService();
    enterAndExitAllDataLineOption.series[1].data = result.data?.map(v => v.number) || []
    enterAndExitAllDataLineChart.setOption(enterAndExitAllDataLineOption);
}

const getEnterAndExitData = async () => {
    let params = {
        park_id: parkingId.value ? parkingId.value : null
    }
    let result = await getEnterDataService(params);
    enterAndExitLineOption.xAxis.data = result.data?.map(v => v.date) || []
    enterAndExitLineOption.series[0].data = result.data?.map(v => v.number) || []

    result = await getExitDataService(params);
    enterAndExitLineOption.series[1].data = result.data?.map(v => v.number) || []
    enterAndExitLineChart.setOption(enterAndExitLineOption);
}
import { getRechargesDataService } from '@/api/recharges';
const getRechargesData = async () => {
    let result = await getRechargesDataService()
    rechargesLineOption.xAxis.data=result.data?.map(v => v.date) || []
    rechargesLineOption.series[0].data = result.data?.map(v => v.value) || []
    rechargesLineOption.series[1].data = result.data?.map(v => v.number) || []
    rechargesLineChart.setOption(rechargesLineOption)
}

import { getOrdersAllDataService,getOrdersDataService } from '@/api/orders';
const getOrdersAllData = async()=>{
    let result =await getOrdersAllDataService()
    ordersAllDataLineOption.xAxis.data=result.data?.map(v => v.date) || []
    ordersAllDataLineOption.series[0].data = result.data?.map(v => v.value) || []
    ordersAllDataLineChart.setOption(ordersAllDataLineOption)
}

const getOrdersData = async() =>{
    let params = {
        park_id: parkingId.value ? parkingId.value : null
    }
    let result =await getOrdersDataService(params)
    ordersDataLineOption.xAxis.data=result.data?.map(v => v.date) || []
    ordersDataLineOption.series[0].data = result.data?.map(v => v.value) || []
    ordersDataLineChart.setOption(ordersDataLineOption)
}
onMounted(async () => {
    await initialization();
    //超级管理员，总体
    if (adminInfoStore.info.permissions === 1 && methodId.value === 0) {
        getEnterAllNumber();
        getExitAllNumber();
        getAdminNumber();
        enterAndExitAllDataLineChart = echarts.init(enterAndExitAllDataLineRef.value);
        ordersAllDataLineChart = echarts.init(ordersAllDataLineRef.value)
        getEnterAndExitAllData();
        getOrdersAllData();

    }
    else if (parking.value.length !== 0 && parkingId.value !== null) {
        getEnterNumberByParkingId();
        getExitNumberByParkingId();
        enterAndExitLineChart = echarts.init(enterAndExitLineRef.value);
        ordersDataLineChart = echarts.init(ordersDataLineRef.value)

        getEnterAndExitData()
        getOrdersData()
    }
    rechargesLineChart = echarts.init(rechargesLineRef.value);

    getRechargesData();


})
const changeMethod = async () => {
    if (methodId.value === 0) {
        getEnterAllNumber();
        getExitAllNumber();
        getAdminNumber();

        getUserData();
        getRechargesData();
        nextTick(() => {
            enterAndExitAllDataLineChart = echarts.init(enterAndExitAllDataLineRef.value);
            ordersAllDataLineChart = echarts.init(ordersAllDataLineRef.value)
            getEnterAndExitAllData();
            getOrdersAllData();
        })
    }
    if (methodId.value === 1) {
        nextTick(() => {
            enterAndExitLineChart = echarts.init(enterAndExitLineRef.value);
            ordersDataLineChart = echarts.init(ordersDataLineRef.value)
            changeParkId();
        })
    }
}
const changeParkId = () => {
    getUserData();
    getRechargesData();


    getEnterNumberByParkingId();
    getExitNumberByParkingId();
    getEnterAndExitData()
    getOrdersData()
}
</script>



<template>
    <el-card class="page-container">
        <template #header>
            <div class="header">
                <span>图表统计</span>
                <div>
                    <span>请选择停车场:</span>
                    <el-select v-model="parkingId" placeholder="请选择停车场" style="width:150px"
                        :disabled="methodId === 0 && adminInfoStore.info.permissions === 1" @change="changeParkId">
                        <el-option v-for="p in parking" :key="p.id" :label="p.park_name" :value="p.id">
                        </el-option>
                    </el-select>
                </div>
                <div v-if="adminInfoStore.info.permissions === 1">
                    <span>请选择查询方式:</span>
                    <el-select v-model="methodId" placeholder="请选择查询方式" style="width:150px" @change="changeMethod">
                        <el-option v-for="m in method" :key="m.id" :label="m.method_name" :value="m.id">
                        </el-option>
                    </el-select>
                </div>
            </div>
        </template>
        <div>
            <el-row :gutter="10">
                <!-- 用户数 -->
                <el-col :span="8">
                    <el-card>
                        <div>
                            <span class="font">当前用户数为：</span>
                            <span class="data">{{ userNumber }}</span>
                            <span class="font"> 名</span>
                        </div>
                        <div>
                            <span class="font">今日新增用户数为：</span>
                            <span class="data">{{ todayNewUserNumber }}</span>
                            <span class="font"> 名</span>
                        </div>
                    </el-card>
                </el-col>
                <!-- 出入库 -->
                <el-col :span="8">
                    <el-card v-show="adminInfoStore.info.permissions === 1 && methodId === 0">
                        <div v-show="methodId === 0">
                            <span class="font">今日入库总数为：</span>
                            <span class="data">{{ enterNumber }}</span>
                            <span class="font"> 次</span>
                        </div>
                        <div v-show="methodId === 0">
                            <span class="font">今日出库总数为：</span>
                            <span class="data">{{ exitNumber }}</span>
                            <span class="font"> 次</span>
                        </div>
                    </el-card>
                    <el-card
                        v-show="(adminInfoStore.info.permissions === 1 && methodId === 1) || adminInfoStore.info.permissions === 0">
                        <div>
                            <span class="font">今日入库数为：</span>
                            <span class="data">{{ enterNumber }}</span>
                            <span class="font"> 次</span>
                        </div>
                        <div>
                            <span class="font">今日出库数为：</span>
                            <span class="data">{{ exitNumber }}</span>
                            <span class="font"> 次</span>
                        </div>
                    </el-card>
                </el-col>
                <el-col :span="8">
                    <el-card v-show="adminInfoStore.info.permissions === 1 && methodId === 0">
                        <div>
                            <span class="font">停车场总数为：</span>
                            <span class="data">{{ parkingNumber }}</span>
                        </div>
                        <div>
                            <span class="font">管理员总数为：</span>
                            <span class="data">{{ adminNumber }}</span>
                        </div>
                    </el-card>
                </el-col>
            </el-row>
            <el-row :gutter="10">
                <el-col :span="12">
                    <el-card v-if="adminInfoStore.info.permissions === 1 && methodId === 0">
                        <div ref="enterAndExitAllDataLineRef" style="width: 100%;height: 400px;"></div>
                    </el-card>
                    <el-card
                        v-else-if="(adminInfoStore.info.permissions === 1 && methodId === 1) || adminInfoStore.info.permissions === 0">
                        <div ref="enterAndExitLineRef" style="width: 100%;height: 400px;"></div>
                    </el-card>
                </el-col>
                <el-col :span="12">
                    <el-card>
                        <div ref="rechargesLineRef" style="width: 100%;height: 400px;"></div>
                    </el-card>
                </el-col>
            </el-row>
            <el-row :gutter="10">
                <el-col :span="12">
                    <el-card v-if="adminInfoStore.info.permissions === 1 && methodId === 0">
                        <div ref="ordersAllDataLineRef" style="width: 100%;height: 400px;"></div>
                    </el-card>
                    <el-card
                        v-else-if="(adminInfoStore.info.permissions === 1 && methodId === 1) || adminInfoStore.info.permissions === 0">
                        <div ref="ordersDataLineRef" style="width: 100%;height: 400px;"></div>
                    </el-card>
                </el-col>
                <el-col :span="12">
                    <el-card>

                    </el-card>
                </el-col>
            </el-row>
        </div>
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

.el-row {
    margin-bottom: 10px;
}

.font {
    font-size: 28px;
}

.data {
    font-size: 32px;
}
</style>async
