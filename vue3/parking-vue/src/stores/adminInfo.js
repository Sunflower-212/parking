import { defineStore } from "pinia";
import {ref} from 'vue'
const useAdminInfoStore = defineStore('adminInfo',()=>{
    //定义状态相关内容
    const info = ref({ permissions: null,admin_name:null })

    const setInfo = (newInfo)=>{
        info.value = newInfo
    }

    const removeInfo = ()=>{
        info.value={ permissions: null }
    }

    return {info,setInfo,removeInfo}
},{persist:true})
export default useAdminInfoStore;
