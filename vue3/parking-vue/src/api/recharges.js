import request from '@/utils/request.js'


//用户充值记录查询,包含条件查询
export const userRechargesListService=(params)=>{
    return request.get('/recharges/userList',{params:params})
}
//用户取消充值
export const userCancelRechargesService=(cancelDate)=>{
    const params = new URLSearchParams()
    for(let key in cancelDate){
        params.append(key,cancelDate[key]);
    }
    return request.post('/user/cancelRecharges',params)

}

//管理员查询充值记录
export const adminGetRechargesPageListService=(params)=>{
    return request.get('/recharges/pageList',{params:params})
}


export const getRechargesDataService=()=>{
    return request.get('/recharges/data')
}
