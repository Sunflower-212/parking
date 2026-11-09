import request from '@/utils/request.js'

//获取管理员信息的接口
export const adminInfoService=()=>{
    return request.get('/admin/adminInfo')
}

//管理员登出的接口
export const adminLogoutService=()=>{
    return request.get('/admin/logout')
}

//管理员列表
export const adminListService=()=>{
    return request.get('/admin/list')
}

//管理员分页列表
export const adminPageListService=(params)=>{
    return request.get('/admin/pageList',{params:params})
}

//超级管理员重置密码
export const rootResetPasswordService=(params)=>{
    return request.patch('/admin/resetPassword',params)
}

//超级管理员删除管理员
export const rootDeleteAdminService=(admin_name)=>{
    return request.delete('/admin/delete?admin_name='+admin_name)
}

//超级管理员添加管理员
export const adminAddService=(adminModel)=>{
    return request.post('/admin/addAdmin',adminModel)
}

export const getAdminNumberService=()=>{
    return request.get('/admin/number')
}
