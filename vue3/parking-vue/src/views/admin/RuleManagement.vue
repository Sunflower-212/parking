<script lang="ts" setup>
import{
    Edit,Delete
    } from '@element-plus/icons-vue'
import {ref} from 'vue'
const selectRuleName = ref('')
const dialogVisible = ref(false)
const title =ref('')
const billingRules = ref([
    {
        id:'',
        rule_name:'',
        free_time:'',
        charging_unit:'',
        unit_cost:'',
        create_time:''
    }
])
const validateUnitCost = (rule, value, callback) => {
  if (!value) {
    return callback(new Error('请输入单位费用'));
  }
  const cost = parseFloat(value);
  if (isNaN(cost) || cost < 0.01) {
    return callback(new Error('单位费用必须是一个大于0.01的正数'));
  }
  callback();
};
const rules = {
    rule_name:[
        { required: true, message: '请输入规则名', trigger: 'blur' },
        { min: 1, max: 20, message: '长度为1-20位', trigger: 'blur' }
    ],
    free_time:[
        {required: true, message: '请输入免费时长', trigger: 'blur'},
        {pattern: /^[1-9]\d*$/, message: '免费时长必须是一个不以0开头的正整数', trigger: 'blur'}
    ],
    charging_unit:[
        {required: true, message: '请输入单位时长', trigger: 'blur'},
        {pattern: /^[1-9]\d*$/, message: '单位时长必须是一个不以0开头的正整数', trigger: 'blur'}
    ],
    unit_cost:[
        { validator: validateUnitCost, trigger: 'blur'}
    ]
}

const ruleModel = ref(
    {
        id:'',
        rule_name:'',
        free_time:'',
        charging_unit:'',
        unit_cost:''
    }
)
import { ruleListService} from '@/api/rule.js'
const ruleList=async()=>{
    let params = {
        rule_name : selectRuleName.value?selectRuleName.value:null,
    }
    let result = await ruleListService(params)
    billingRules.value=result.data
    ElMessage.success('查询成功')
}
ruleList();

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
const addRuleBtn = ()=>{
    ruleModel.value.id = ''
    ruleModel.value.rule_name=''
    ruleModel.value.free_time=''
    ruleModel.value.charging_unit=''
    ruleModel.value.unit_cost=''
    title.value='添加规则'
    dialogVisible.value=true
}
const updateRule=(row)=>{
    ruleModel.value.id = row.id
    ruleModel.value.rule_name=row.rule_name
    ruleModel.value.free_time=row.free_time
    ruleModel.value.charging_unit=row.charging_unit
    ruleModel.value.unit_cost=row.unit_cost
    title.value='修改规则'
    dialogVisible.value=true
}
import { ruleAddService,ruleUpdateService,ruleDeleteService } from '@/api/rule';
import { ElMessage,ElMessageBox } from 'element-plus';
const ruleAdd = async()=>{
    let result = await ruleAddService(ruleModel.value)
    ElMessage.success('添加规则:'+ruleModel.value.rule_name+'成功')
    dialogVisible.value=false
    ruleList();
}

const ruleUpdate = async()=>{
    let result = await ruleUpdateService(ruleModel.value)
    ElMessage.success('修改规则成功')
    dialogVisible.value=false
    ruleList();
}

const deleteRule= async(row)=>{
    try {
        // 确认用户是否真的要删除
        await ElMessageBox.confirm(
            '你确定要删除规则: '+row.rule_name+' 吗?',
            '温馨提示',
            {
                confirmButtonText: '确认',
                cancelButtonText: '取消',
                type: 'warning'
            }
        );
        // 用户点击了“确认”，现在尝试删除停车场
        try {
            //删除规则
            await ruleDeleteService(row.id);
            ElMessage({
                type:'success',
                message:'删除规则成功'
            })
            //刷新列表
            ruleList();
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
                <span>规则管理</span>
                <div class="extra">
                    <el-button type="primary" @click="addRuleBtn">新增规则</el-button>
                </div>
            </div>
        </template>
        <!-- 搜索表单 -->
        <el-form  inline >
            <el-form-item label="规则名称:">
                <el-input v-model="selectRuleName" placeholder="请输入规则名" style="width:140px"></el-input>
            </el-form-item>
            <el-form-item >
                <el-button type="primary" @click="ruleList">搜索</el-button>
                <el-button @click="selectRuleName='',ruleList()">重置</el-button>
            </el-form-item>
        </el-form>
        <el-table :data="billingRules" height="420" stripe style="width: 100%">
            <el-table-column prop="id" label="规则id" width="110" />
            <el-table-column prop="rule_name" label="规则名" width="200" />
            <el-table-column prop="free_time" label="免费时长(分)" width="150" />
            <el-table-column prop="charging_unit" label="计费单位(分)" width="150" />
            <el-table-column prop="unit_cost" label="单位费用(元)" width="150" />
            <el-table-column prop="create_time" label="创建时间" width="200" :formatter="formatDateTime" />
            <el-table-column label="操作" width="100">
                <template #default="{ row }">
                    <el-button :icon="Edit" circle plain type="primary" @click="updateRule(row)"></el-button>
                    <el-button :icon="Delete" circle plain type="danger" @click="deleteRule(row)"></el-button>
                </template>
            </el-table-column>
            <template #empty>
                <el-empty description="没有数据" />
            </template>
        </el-table>
        <!-- 添加规则弹窗 -->
        <el-dialog v-model="dialogVisible" :title="title" width="30%" @close="dialogVisible=false">
            <el-form :model="ruleModel" :rules="rules" label-width="120px" style="padding-right: 30px">
                <el-form-item label="规则名" prop="rule_name">
                    <el-input v-model="ruleModel.rule_name" :disabled="title=='修改规则'"></el-input>
                </el-form-item>
                <el-form-item label="免费时长(分)" prop="free_time">
                    <el-input type="number" v-model="ruleModel.free_time" ></el-input>
                </el-form-item>
                <el-form-item label="单位时长(分)" prop="charging_unit">
                    <el-input type="number" v-model="ruleModel.charging_unit" ></el-input>
                </el-form-item>
                <el-form-item label="单位费用(元)" prop="unit_cost">
                    <el-input v-model="ruleModel.unit_cost" ></el-input>
                </el-form-item>
            </el-form>
            <template #footer>
                <span class="dialog-footer">
                    <el-button @click="dialogVisible=false">取消</el-button>
                    <el-button type="primary" @click="title=='添加规则'?ruleAdd():ruleUpdate()"> 确认 </el-button>
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
