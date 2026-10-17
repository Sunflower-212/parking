import request from '@/utils/request.js'

//停车场查询
export const parkingListService=()=>{
    return request.get('/parking/list')
}

//管理员查询自己管理的停车场
export const adminParkingListService=()=>{
    return request.get('/parking/adminGetParkingList')
}

//管理员查询自己管理停车场的详细信息
export const adminGetParkingListService=(params)=>{
    return request.get('/parking/selectParking',{params,params})
}

//超级管理员查询所有停车场信息
export const rootGetParkingListService=(params)=>{
    return request.get('/parking/selectAllParking',{params,params})
}

//管理员添加停车场
export const adminParkingAddService=(parkingData)=>{
    return request.post('/parking/addParking',parkingData)
}

//超级管理员添加停车场
export const rootParkingAddService=(parkingData)=>{
    return request.post('/parking/rootAddParking',parkingData)
}

//管理员修改停车场
export const adminParkingUpdateService=(parkingData)=>{
    return request.put('/parking/updateParking',parkingData)
}
//管理员修改停车场
export const rootParkingUpdateService=(parkingData)=>{
    return request.put('/parking/rootUpdateParking',parkingData)
}

//删除停车场
export const deleteParkingService=(parkingName)=>{
    return request.delete('/parking/delete?park_name='+parkingName)
}
