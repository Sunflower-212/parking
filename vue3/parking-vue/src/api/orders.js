import request from '@/utils/request.js'
//import { useTokenStore } from '@/stores/token.js'

//用户订单查询,包含条件查询
export const userOrdersListService=(params)=>{
    //const tokenStore = useTokenStore();
    //在pinia中定义的数据都不需要.value，可以直接获取
    /* 在请求拦截器中携带token，就不用手动添加token
    return request.get('/orders/userList',{headers:{'Authorization':tokenStore.token}}) */
    return request.get('/orders/userList',{params:params})
}


export const adminOrdersListService=(params)=>{
    return request.get('/orders/adminPageList',{params:params})
}

export const exitUploadService=(formData)=>{
    return request.post('/orders/upload',formData,
    {headers: {'Content-Type': 'multipart/form-data'}})
}

export const getExitAllNumberService=()=>{
    return request.get('/orders/allNumber')
}
export const getExitNumberByParkingIdService=(params)=>{
    return request.get('/orders/number',{params:params})
}

export const getExitAllDataService=()=>{
    return request.get('/orders/exitAllData')
}

export const getExitDataService=(params)=>{
    return request.get('/orders/exitData',{params:params})
}


export const getOrdersAllDataService=()=>{
    return request.get('/orders/allData')
}

export const getOrdersDataService=(params)=>{
    return request.get('/orders/data',{params:params})
}
