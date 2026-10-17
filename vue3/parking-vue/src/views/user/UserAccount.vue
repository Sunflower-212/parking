<script setup>
import { ref } from 'vue'
import { Avatar, Search } from '@element-plus/icons-vue'
import useUserInfoStore from '@/stores/userInfo.js';
const userInfoStore = useUserInfoStore();


const status = ref(userInfoStore.info.status)
const balance = ref(userInfoStore.info.balance)
const payModel = ref({
    amounts: '',
    pay_method: 0
})
const rules = {
    amounts: [
        { required: true, message: '请输入充值金额', trigger: 'blur' },
        {
            type: "number",
            message: "充值金额必须为数值",
            trigger: 'blur'
        },
        {
            type: "number",
            min: 0,
            message: "充值金额必须大于0",
            trigger: 'blur'
        }
    ]
}
const validateInteger = (rule, value, callback) => {
    if (!Number.isInteger(Number(value))) {
        callback(new Error('天数必须为整数'));
    } else {
        callback();
    }
};
const vipRules = {
    days: [
        { required: true, message: '请输入天数', trigger: 'blur' },
        {
            type: "number",
            message: "天数必须为数值",
            trigger: 'blur'
        },
        {
            type: "number",
            min: 1,
            message: "天数必须大于0",
            trigger: 'blur'
        },
        {
            validator: validateInteger,
            trigger: 'blur'
        }
    ]
}
const formatDateTime = (date) => {
    if (date) {
        const end_time = new Date(date);
        // 使用 toLocaleString 方法来格式化日期时间，不显示 T

        const options = {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit'
        };
        return end_time.toLocaleString('zh-CN', options);
    }
    return '';
}
const vip_end_time = ref(formatDateTime(userInfoStore.info.vip_end_time))
const dialogVisible = ref(false)
const vipDialog = ref(false)
const vipTitle = ref('')
const vipModel = ref({
    days: null
})

import { userPayService } from '@/api/user.js'
import DOMPurify from 'dompurify';
const showPayDialog = ref(false);
const sanitizedFormHtml = ref('');
const reRechargeName = ref('');
const reRechargeId = ref('');
const reAmounts = ref('')

const usePay = async () => {

    let result = await userPayService(payModel.value);
    dialogVisible.value = false;
    if (payModel.value.pay_method === 0) {
        showPayDialog.value = true;
        reRechargeName.value = result.data.rechargeName;
        reAmounts.value = result.data.reAmounts;
        reRechargeId.value = result.data.rechargeId;
        // 使用DOMPurify清理HTML
        sanitizedFormHtml.value = DOMPurify.sanitize(result.data.form);
    } else if (payModel.value.pay_method === 1) {
        //微信支付
        reRechargeName.value = result.data.rechargeName;
        reAmounts.value = result.data.reAmounts;
        reRechargeId.value = result.data.rechargeId;
    }


}
import { userCancelRechargesService } from '@/api/recharges.js';
import { ElMessage } from 'element-plus';
const aliPayClose = async () => {
    //发送取消订单请求
    let cancelData = {
        rechargesId: reRechargeId.value,
    }
    await userCancelRechargesService(cancelData);
    ElMessage({
        type: 'info',
        message: '取消支付'
    })
    showPayDialog.value = false;
    reRechargeName.value = null;
    reAmounts.value = null;
    reRechargeId.value = null;
};

const submitForm = () => {
    // 获取表单并手动提交
    const form = document.querySelector('form[name="punchout_form"]');
    if (form) {
        form.submit();
    }
};
const myColor = ref('#00A1FF')
const changeColor = () => {
    if (payModel.value.pay_method === '0') {
        myColor.value = '#00A1FF';
    } else if (payModel.value.pay_method === '1') {
        myColor.value = '#1AAD19';
    }
}
import { userInfoService } from '@/api/user.js';
//调用函数获取用户详细信息
const getUserInfo = async()=>{
    //调用接口
    let result = await userInfoService();

    //数据存储到pinia中
    userInfoStore.setInfo(result.data);
}

const getVipDialog = () => {
    vipModel.value.days = null
    vipTitle.value = "开通会员"
    vipDialog.value = true;
}
import {getVipService ,renewalVipService} from '@/api/user.js'
const getVip = async() => {
    let result = await getVipService(vipModel.value.days)
    await getUserInfo();
    vip_end_time.value=formatDateTime(userInfoStore.info.vip_end_time)
    vipDialog.value = false;
}


const renewalVipDialog = () => {
    vipModel.value.days = null
    vipTitle.value = "续费会员"
    vipDialog.value = true;
}

const renewalVip = async() => {
    let result = await renewalVipService(vipModel.value.days)
    await getUserInfo();
    vip_end_time.value=formatDateTime(userInfoStore.info.vip_end_time)
    vipDialog.value = false;
}
</script>

<template>
    <el-card class="page-container">
        <template #header>
            <div class="header">
                <span>个人账户</span>
            </div>
        </template>
        <div class="container">
            <div class="user_info" v-if="status === 0">
                <el-icon class="avatarIcon">
                    <Avatar />
                </el-icon>
                <div style="font-size:30px; margin-bottom: 20px;">身 份:
                    <el-tag type="danger" size="large" round
                        :style="{ padding: '10px 10px', fontSize: '25px', lineHeight: '25px' }">
                        普通用户
                    </el-tag>
                </div>
                <el-button type="primary" size='large' @click="getVipDialog" style="font-size:20px; margin-top: 150px;">
                    开通会员
                </el-button>
            </div>
            <div class="vip_info" v-if="status === 1">
                <el-icon class="avatarIcon">
                    <Avatar />
                </el-icon>
                <div style="font-size:30px; margin-bottom: 20px;">身 份:
                    <el-tag type="danger" size="large" round
                        :style="{ padding: '10px 10px', fontSize: '25px', lineHeight: '25px' }">
                        会 员
                    </el-tag>
                </div>
                <div style="font-size:20px; margin-bottom: 50px;">
                    有效期至:
                    <el-tag type="danger" size="large" round
                        :style="{ padding: '10px 10px', fontSize: '25px', lineHeight: '25px' }">
                        {{ vip_end_time }}
                    </el-tag>
                </div>

                <el-button type="primary" size='large' @click="renewalVipDialog"
                    style="font-size:20px; margin-top: 80px;">
                    <font-awesome-icon :icon="['fas', 'arrows-spin']" />
                    续费会员
                </el-button>
            </div>
            <el-divider direction="vertical" border-style="dashed" style="height: 100%;" />
            <div class="account-balance">
                <font-awesome-icon :icon="['fas', 'wallet']"
                    style="font-size: 90px;opacity: 0.5;margin-bottom: 30px;"></font-awesome-icon>
                <div style="font-size:30px; margin-bottom: 60px;">余 额:
                    <el-tag type="success" size="large" round
                        :style="{ padding: '20px 10px', fontSize: '30px', lineHeight: '25px' }">
                        {{ balance }} 元
                    </el-tag>
                </div>
                <div>
                    <el-button type="primary" size='large' @click="dialogVisible = true"
                        style="margin-top: 110px;font-size:20px;">
                        充值
                    </el-button>
                    <el-button type="primary" size='large' @click="$router.push('/user/rechargeHistory')" color="khaki"
                        style="margin-top: 110px;font-size:20px;">
                        <el-icon>
                            <Search />
                        </el-icon>
                        充值记录
                    </el-button>
                    <el-button type="primary" size='large' @click="$router.push('/user/orderHistory')"
                        color="mediumpurple" style="margin-top: 110px;font-size:20px;">
                        <el-icon>
                            <Search />
                        </el-icon>
                        消费记录
                    </el-button>
                </div>
            </div>
        </div>
        <!-- 充值弹窗 -->
        <el-dialog v-model="dialogVisible" title="充值" width="30%"
            @close="dialogVisible = false, payModel.amounts = '', payModel.pay_method = 0">
            <el-form :model="payModel" :rules="rules" label-width="100px" style="padding-right: 30px;">
                <el-form-item label="充值金额" prop="amounts">
                    <el-input type="number" v-model.number="payModel.amounts">
                    </el-input>
                </el-form-item>
                <el-form-item label="支付方式" style="margin-top: 20px">
                    <el-radio-group v-model="payModel.pay_method" @change="changeColor" :fill="myColor">
                        <el-radio-button value=0 style="margin-right: 20px;">
                            <font-awesome-icon :icon="['fab', 'alipay']"
                                style="font-size: 22px;margin-right: 2px;"></font-awesome-icon>
                            支付宝
                        </el-radio-button>
                        <el-radio-button value=1>
                            <font-awesome-icon :icon="['fab', 'weixin']"
                                style="font-size: 22px;margin-right: 2px;"></font-awesome-icon>
                            微 信
                        </el-radio-button>
                    </el-radio-group>
                </el-form-item>
            </el-form>
            <template #footer>
                <span class="dialog-footer">
                    <el-button
                        @click="dialogVisible = false, payModel.amounts = '', payModel.pay_method = 0">取消</el-button>
                    <el-button type="primary" @click="usePay">确认</el-button>
                </span>
            </template>
        </el-dialog>

        <el-dialog v-model="vipDialog" :title="vipTitle" width="30%" @close="vipDialog = false, vipModel.days = null">
            <el-form :model="vipModel" :rules="vipRules" label-width="100px" style="padding-right: 30px;">
                <el-form-item :label="vipTitle === '开通会员' ? '开通天数' : '续费天数'" prop="days">
                    <el-input type="number" aria-label="天数" v-model.number="vipModel.days"></el-input>
                </el-form-item>
            </el-form>

            <template #footer>
                <span class="dialog-footer">
                    <el-button @click="vipDialog = false, days = null">取消</el-button>
                    <el-button type="primary" :disabled="vipModel.days===null" @click="vipTitle === '开通会员' ? getVip() : renewalVip()">确认</el-button>
                </span>
            </template>
        </el-dialog>
    </el-card>
    <el-dialog v-model="showPayDialog" title="支付宝支付信息确认窗口" width="30%" @close="aliPayClose">
        <div style="font-size: 20px;">充值名称: <span style="color: aqua;">{{ reRechargeName }}</span></div>
        <div style="font-size: 20px;">充值金额: <span style="color: aqua;">{{ reAmounts }}</span></div>
        <div style="font-size: 20px;">充值单号: <span style="color: aqua;">{{ reRechargeId }}</span></div>
        <div v-html="sanitizedFormHtml"></div>
        <el-button type="primary" @click="submitForm">立即支付</el-button>

    </el-dialog>
</template>
<style scoped>
.container {
    display: flex;
    height: 480px;
    width: auto;
    align-items: stretch;
    /* 使子元素与容器等高 */
    background-color: rgb(99, 208, 99);
}

.user_info {
    display: flex;
    flex: 1;
    /* 占据容器的一半空间 */
    flex-direction: column;
    align-items: center;
    padding: 20px;
}

.vip_info {
    display: flex;
    flex: 1;
    /* 占据容器的一半空间 */
    flex-direction: column;
    align-items: center;
    padding: 20px;

}

.account-balance {
    display: flex;
    flex: 1;
    /* 占据容器的一半空间 */
    flex-direction: column;
    align-items: center;
    padding: 20px;
}

.avatarIcon {
    font-size: 100px;
    opacity: 0.5;
    margin-bottom: 20px;
}
</style>
