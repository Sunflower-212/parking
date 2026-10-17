import request from '@/utils/request.js'

//查询规则列表
export const ruleListService=(params)=>{
    return request.get('/rule/select',{params:params})
}

//添加规则接口
export const ruleAddService=(ruleData)=>{
    return request.post('/rule/create',ruleData)
}

//修改规则接口
export const ruleUpdateService=(ruleData)=>{
    return request.put('/rule/update',ruleData)
}

//删除规则接口
export const ruleDeleteService=(ruleId)=>{
    return request.delete('/rule/delete?id='+ruleId)
}
