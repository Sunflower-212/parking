//导入request.js请求工具
import request from '@/utils/request.js'

//提供调用注册接口的函数
export const userRegisterService = (registerData)=>{
    //借助UrlSearchParams完成传递
    const params = new URLSearchParams()
    for(let key in registerData){
        params.append(key,registerData[key]);
    }
    return request.post('/user/register',params);
}

//登录
export const loginService=(loginData,idRadio)=>{
    //借助UrlSearchParams完成传递
    const params = new URLSearchParams()
    for(let key in loginData){
        params.append(key,loginData[key]);
    }
    if(idRadio==='1'){
        return request.post('/user/login',params)
    }else{
        return request.post('/admin/login',params)
    }

}

export const casLoginService=()=>{
    return request.get('/oauth/authorize')
}


//获取用户详细信息
export const userInfoService =()=>{
    return request.get('/user/userInfo')
}

//退出登录
export const userLogoutService = () =>{
    return request.get('/user/logout')
}

//更新用户信息
export const updateUserInfoService=(userInfoData)=>{
    return request.put('/user/update',userInfoData)
}

//更换用户头像
export const userAvatarUpdateService=(AvatarUrl)=>{
    const params = new URLSearchParams();
    params.append('avatarUrl',AvatarUrl)
    return request.patch('/user/updateAvatar',params)
}

//用户充值
export const userPayService=(rechargeDate)=>{
    //借助UrlSearchParams完成传递
    const params = new URLSearchParams()
    for(let key in rechargeDate){
        params.append(key,rechargeDate[key]);
    }
    return request.post('/user/recharge',params)
}

//用户列表
export const userPageListService=(params)=>{
    return request.get('/user/pageList',{params:params})
}
//用户修改密码
export const userUpdatePasswordService=(params)=>{
    return request.patch('/user/updatePassword',params)
}


//管理员添加用户
export const userAddService=(userModel)=>{
    return request.post('/user/addUser',userModel)
}

//管理员重置密码
export const adminResetPasswordService=(params)=>{
    return request.patch('/user/resetPassword',params)
}

//管理员删除用户
export const adminDeleteUserService=(user_name)=>{
    return request.delete('/user/delete?user_name='+user_name)
}

//管理员获取所有用户数量
export const getUserDataService=()=>{
    return request.get('/user/data')
}

export const getVipService=(days)=>{
    const params = new URLSearchParams();
    params.append('days',days)
    return request.patch('/user/getVip',params)
}

export const renewalVipService=(days)=>{
    const params = new URLSearchParams();
    params.append('days',days)
    return request.patch('/user/vipRenewal',params)
}
