import request from '@/utils/request.js'

//获取车位列表
export const getSpacesListService=(params)=>{
    return request.get('/spaces/get',{params:params})
}


//添加车位
export const spaceAddService=(spaceData)=>{
    return request.post('/spaces/addSpace',spaceData)
}


//根据车位名查询车位
export const spacesQueryService=(params)=>{
    return request.get('/spaces/querySpaces',{params:params})
}


//修改车位信息
export const spaceUpdateService=(spaceData)=>{
    return request.put('/spaces/update',spaceData)
}

//删除车位
export const spaceDeleteBatchService=(spaceList)=>{
    return request.delete('/spaces/deleteBatch',{data:spaceList})
}
