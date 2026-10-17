import request from '@/utils/request.js'


//进站的接口
export const enterUploadService=(formData)=>{
    return request.post('/enter/upload',formData,
    {headers: {'Content-Type': 'multipart/form-data'}})
}

export const isEnterOrReservationService=()=>{
    return request.get('/enter/userEnterOrReservation')
}

//预约的接口
export const reservationService=(spacesData)=>{
    return request.patch('/enter/reservation',spacesData)
}
//取消预约接口
export const cancelReservationService=(enterId)=>{
    return request.post('/enter/cancelReservation?id='+enterId)

}


export const userEnterPageListService=(params)=>{
    return request.get('/enter/userPageList',{params:params})
}


export const getEnterAllNumberService=()=>{
    return request.get('/enter/allNumber')
}
export const getEnterNumberByParkingIdService=(params)=>{
    return request.get('/enter/number',{params:params})
}

export const getEnterAllDataService=()=>{
    return request.get('/enter/allData')
}

export const getEnterDataService=(params)=>{
    return request.get('/enter/data',{params:params})
}
