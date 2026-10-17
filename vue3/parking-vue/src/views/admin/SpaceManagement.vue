<script lang="ts" setup>
import {Plus} from '@element-plus/icons-vue';
import { ElMessage,ElMessageBox } from 'element-plus';
import {ref,onMounted} from 'vue'
import useAdminInfoStore from '@/stores/adminInfo';
const adminInfoStore = useAdminInfoStore();
const permissions = adminInfoStore.info.permissions;
const maxRow = ref(0)// 最大行数
const maxCol = ref(0)// 最大列数
const spaces = ref([]); // 存储车位数据
const parkingCanvas = ref<HTMLCanvasElement | null>(null);
const selectedSpaces = ref([])
const cellWidth = 50
const cellHeight = 50
const selectSpaceName = ref('')
const parkingId =ref('')
const parkingStorey = ref('')
const ctx = ref()
interface Spaces{
    id:string,
    park_id:string,
    park_name:string,
    spaces_name:string,
    spaces_col:string,
    spaces_row:string,
    spaces_storey:string,
    spaces_flag:string,
}
const parking = ref([
    {
        id:'',
        park_name:"",
        park_storey:'',
    }
])
const addParking= ref([
    {
        id:'',
        park_name:"",
        park_storey:'',
    }
])
const storeyOptions = ref([
    {
        id:null,
        storey:null,
    }
])
const addStoreyOptions = ref([
    {
        id:null,
        storey:null,
    }
])
const flag = [
    {
        id:0,
        name:'空闲车位',
    },
    {
        id:2,
        name:'道路',
    },
    {
        id:3,
        name:'墙壁',
    },
    {
        id:4,
        name:'出入口',
    },
]
const nullCarImg = ref()
const existCarImg = ref()
const doorImg = ref()
const selectedCarImg = ref()
const selectedDoorImg=ref()
const selectedImg =ref()
const addDialog = ref(false)
const title = ref('')
const spaceModel=ref({
    park_name:'',
    spaces_name:'',
    spaces_row:null,
    spaces_col:null,
    spaces_storey:null,
    spaces_flag:null,
    user_name:'',
})
const addRules = {
    park_name:[
        {required: true, message: '请选择停车场', trigger: 'blur'}
    ],
    spaces_name:[
        { required: true, message: '请输入车位名', trigger: 'blur' },
        { min: 1, max: 10, message: '长度为1-10位', trigger: 'blur' }
    ],
    spaces_row:[
        { required: true, message: '请输入车位所在行', trigger: 'blur' },
    ],
    spaces_col:[
        { required: true, message: '请输入车位所在列', trigger: 'blur' },
    ],
    spaces_storey:[
        { required: true, message: '请选择楼层', trigger: 'blur' },
    ],
    spaces_flag:[
        { required: true, message: '请选择状态', trigger: 'blur' },
    ]
}
const querySpaces= ref([])
const listDialog = ref(false)
let count=0
const drawAllImage=()=>{
    // 将车位数据放入对应的位置渲染
    let currentIndex = 0; // 初始化当前索引
    spaces.value.forEach((space) => {
        if(!space){
            ctx.value.fillStyle = 'gray';
            let row = Math.floor(currentIndex / maxCol.value);
            let col = currentIndex % maxCol.value;
            const x = col * cellWidth;
            const y = row* cellHeight;
            ctx.value.fillRect(x, y, cellWidth, cellHeight);
        }else{
            const x = (space.spaces_col - 1) * cellWidth;
            const y = (space.spaces_row - 1) * cellHeight;
            if(space.spaces_flag === 0){
                ctx.value.drawImage(nullCarImg.value, x, y, cellWidth, cellHeight);
            }else if(space.spaces_flag === 1){
                ctx.value.drawImage(existCarImg.value, x, y, cellWidth, cellHeight);
            }else if(space.spaces_flag === 4){
                ctx.value.drawImage(doorImg.value, x, y, cellWidth, cellHeight);
            }else{
                ctx.value.fillStyle = getSpaceColor(space);
                ctx.value.fillRect(x, y, cellWidth, cellHeight);
            }
        }
        currentIndex++;
    });
}

import {getSpacesListService} from '@/api/spaces.js'
let requestVersion = 0;
const fetchSpaces = async () => {
    const version = ++requestVersion;
    if(!parkingId.value || !parkingStorey.value) return;
    let params = {
        park_id:parkingId.value,
        spaces_storey:parkingStorey.value,
    }
    let result = await getSpacesListService(params);
    if(version !== requestVersion) return;
    const rawSpaces = result.data || [];
    spaces.value=[];
    selectedSpaces.value=[];
    if(rawSpaces.length<=0){
        if(ctx.value) ctx.value.clearRect(0,0,parkingCanvas.value.width,parkingCanvas.value.height);
        ElMessage.info("暂无车位，请先添加车位")
        return;
    }
    maxRow.value=1;
    maxCol.value=1;
    // 遍历车位数据，找到最大行和列，并转换为一维数组
    rawSpaces.forEach(space => {
        if (space.spaces_row > maxRow.value) maxRow.value = space.spaces_row;
        if (space.spaces_col > maxCol.value) maxCol.value = space.spaces_col;
    });
    // 转换为一维数组，方便遍历
    for (let i = 0; i < maxRow.value * maxCol.value; i++) {
        spaces.value[i] = null; // 初始化所有位置为无车位
    }
    // 将车位数据放入对应的位置
    rawSpaces.forEach(space => {
        const index = (space.spaces_row - 1) * maxCol.value + (space.spaces_col - 1);
        spaces.value[index] = space;
    });
    parkingCanvas.value.width = cellWidth * maxCol.value;
    parkingCanvas.value.height = cellHeight * maxRow.value;
    if(count>0 && ctx.value && nullCarImg.value){
        drawAllImage();
        drawSelectImage();
    }
    count++;
};


const updateParkStorey=async (newValue)=>{
    spaces.value=[]
    selectedSpaces.value=[]
    maxRow.value=0
    maxCol.value=0
    const selectedParking = parking.value.find(p => p.id === newValue);
    ctx.value.clearRect(0, 0, parkingCanvas.value.width, parkingCanvas.value.height);
    if (selectedParking) {
        await updateParkStoreyByParking(selectedParking.park_storey);
    }
}

const updateSpaces = async ()=>{
    spaces.value=[]
    selectedSpaces.value=[]
    maxRow.value=0
    maxCol.value=0

    await fetchSpaces();
    ctx.value.clearRect(0, 0, parkingCanvas.value.width, parkingCanvas.value.height);
    drawAllImage();
    drawSelectImage();
}

const updateParkStoreyByParking= async (storeyCount)=>{
    storeyOptions.value = Array.from({ length: storeyCount }, (_, index) => ({
        id: index + 1, // ID从1开始
        storey: index + 1+'楼', // 楼层从1开始
    }));
    if(storeyOptions.value.length>0){
        parkingStorey.value = storeyOptions.value[0].id
    }
    await fetchSpaces();
}



import { adminParkingListService,parkingListService } from '@/api/parking';
const initialization= async()=>{
    if(permissions===0){
        let result = await adminParkingListService();
        parking.value = result.data;
        addParking.value = result.data;
    }
    else if(permissions===1){
        let result = await parkingListService();
        parking.value = result.data;
        addParking.value = result.data;
    }
    else {
        ElMessage({type:'warning', message:'系统异常：非管理员'})
    }
    // 初始化楼层选项（这里假设默认选择第一个停车场的楼层）
    if (parking.value.length > 0) {
        parkingId.value = parking.value[0].id;
        await updateParkStoreyByParking(parking.value[0].park_storey);
    }else{
        ElMessage.info("没有停车场，请先添加");
        return;
    }

}



const getSpaceColor=(space)=> {
    // 返回基本的填充颜色，用于没有图片的矩形
    switch (space.spaces_flag) {
            case 2: return 'white'; // 道路
            case 3: return 'black'; // 墙壁
            default: return 'gray'; // 其他情况
        }
}

const selectedParkingSpace =(e)=>{
    //获取当前鼠标的位置
    let pageX = e.clientX;
    let pageY = e.clientY;
    //获取canvas边界位置
    let bbox = parkingCanvas.value.getBoundingClientRect();
    const x=Math.ceil((pageX-bbox.left)/cellWidth);
    const y=Math.ceil((pageY-bbox.top)/cellHeight);
    //获取点击到的对象，根据x,y和spaces中的col,row对比
    let parkingSpace = spaces.value.find(space=>{
        if(space){
            if(space.spaces_col===x&&space.spaces_row===y){
                return space;
            }
        }
    })
    //空区域无法点击
    if(!parkingSpace){
        return;
    }
    //查找当前点击的数据在数组中是否存在
    const selectIndex = selectedSpaces.value.findIndex(item=>item.id===parkingSpace.id)
    if(selectIndex>-1){
        //存在，删除
        selectedSpaces.value.splice(selectIndex, 1);

    }else{
        //不存在，添加

        selectedSpaces.value=[...selectedSpaces.value,parkingSpace]

    }
    ctx.value.clearRect(0, 0, parkingCanvas.value.width, parkingCanvas.value.height);
    drawAllImage();
    drawSelectImage();
}
const drawSelectImage=()=>{
    selectedSpaces.value.forEach(space=>{
        const x = (space.spaces_col - 1) * cellWidth;
        const y = (space.spaces_row - 1) * cellHeight;
        ctx.value.strokeStyle = '#1296db'; // 设置边框颜色
        ctx.value.lineWidth = 4; // 设置边框宽度
        if(space.spaces_flag===0||space.spaces_flag===1){
            ctx.value.drawImage(selectedCarImg.value,x,y,cellWidth, cellHeight)

        }else if(space.spaces_flag===4){
            ctx.value.drawImage(selectedDoorImg.value,x,y,cellWidth, cellHeight)
        }else{
            ctx.value.drawImage(selectedImg.value,x,y,cellWidth, cellHeight)
        }
        // 绘制方形边框
        ctx.value.strokeRect(x, y, cellWidth, cellHeight);
    })
}
onMounted(async ()=>{
    ctx.value = parkingCanvas.value.getContext('2d') as CanvasRenderingContext2D;
    await initialization();
    const nullCarImage = new Image();
    const existCarImage = new Image();
    const doorImage = new Image();
    const selectedCarImage = new Image();
    const selectedDoorImage = new Image();
    const selectedImage = new Image();
    let count =0
    const loadcallback=()=>{
        count ++;
        if(count ==6){
            nullCarImg.value=nullCarImage
            existCarImg.value=existCarImage
            doorImg.value=doorImage
            selectedCarImg.value=selectedCarImage
            selectedDoorImg.value = selectedDoorImage
            selectedImg.value = selectedImage
            drawAllImage();
            drawSelectImage();
            return

        }
    }
    nullCarImage.onload=loadcallback;
    existCarImage.onload=loadcallback;
    doorImage.onload=loadcallback;
    selectedCarImage.onload=loadcallback;
    selectedDoorImage.onload=loadcallback;
    selectedImage.onload=loadcallback;

    nullCarImage.src=new URL('../../assets/nullcar.png', import.meta.url).href
    existCarImage.src = new URL('../../assets/existcar.png', import.meta.url).href
    doorImage.src = new URL('../../assets/door.png', import.meta.url).href
    selectedCarImage.src = new URL('../../assets/selectedCar.png', import.meta.url).href
    selectedDoorImage.src = new URL('../../assets/selectedDoor.png', import.meta.url).href
    selectedImage.src = new URL('../../assets/selected.png', import.meta.url).href
})

const addUpdateParkStoreyByParking= async (storeyCount)=>{
    addStoreyOptions.value = Array.from({ length: storeyCount }, (_, index) => ({
        id: index + 1, // ID从1开始
        storey: index + 1+'楼', // 楼层从1开始
    }));
}

const AddUpdateParkStorey=async(newValue)=>{
    spaceModel.value.spaces_storey=null;
    const selectedParking = addParking.value.find(p => p.park_name === newValue);
    if (selectedParking) {
        await addUpdateParkStoreyByParking(selectedParking.park_storey);
    }

}

const addSpace=()=>{
    title.value='添加车位'
    addDialog.value=true;
    spaceModel.value.park_name=null
    spaceModel.value.spaces_name=null
    spaceModel.value.spaces_row=null
    spaceModel.value.spaces_col=null
    spaceModel.value.spaces_storey=null
    spaceModel.value.spaces_flag=null
}
import { spaceAddService } from '@/api/spaces.js';
const spaceAdd=async()=>{
    let result = await spaceAddService(spaceModel.value);
    ElMessage.success('添加车位:'+spaceModel.value.spaces_name+'成功')
    addDialog.value=false
    fetchSpaces();
}

import { spacesQueryService } from '@/api/spaces.js';
const querySpaceList=async()=>{
    querySpaces.value=[]
    listDialog.value = true
    let params={
        park_id:parkingId.value,
        spaces_storey:parkingStorey.value,
        spaces_name:selectSpaceName.value
    }
    let result = await spacesQueryService(params)
    querySpaces.value = result.data
}

const spaceFlagFilterTag=(value:string,row:Spaces)=>{
    return row.spaces_flag === value
}


const updateSpace = ()=>{
    if(selectedSpaces.value.length===0){
        ElMessage.error('请先选择需要修改的车位')
        return;
    }
    if(selectedSpaces.value.length!==1){
        ElMessage.error('只能选择一个需要修改的车位')
        return;
    }
    spaceModel.value.park_name=selectedSpaces.value[0].park_name
    AddUpdateParkStorey(selectedSpaces.value[0].park_name);
    spaceModel.value.spaces_name=selectedSpaces.value[0].spaces_name
    spaceModel.value.spaces_row=selectedSpaces.value[0].spaces_row
    spaceModel.value.spaces_col=selectedSpaces.value[0].spaces_col
    spaceModel.value.spaces_storey=selectedSpaces.value[0].spaces_storey
    spaceModel.value.spaces_flag=selectedSpaces.value[0].spaces_flag
    spaceModel.value.user_name = selectedSpaces.value[0].user_name?selectedSpaces.value[0].user_name:null
    title.value='修改车位'
    addDialog.value=true;

}
import { spaceUpdateService } from '@/api/spaces.js';
const spaceUpdate=async()=>{
    let result = await spaceUpdateService(spaceModel.value)
    addDialog.value=false;
    selectedSpaces.value=[]
    fetchSpaces();
    ElMessage.success('修改车位信息成功！')
}

import { spaceDeleteBatchService } from '@/api/spaces.js';
const spaceDeleteBatch=async()=>{
    if(selectedSpaces.value.length==0){
        ElMessage.error('请先选择需要删除的车位')
        return;
    }

    try{
        await ElMessageBox.confirm(
            '你确定要删除这些车位吗?',
            '温馨提示',
            {
                confirmButtonText: '确认',
                cancelButtonText: '取消',
                type: 'warning'
            }
        );
        try{
            let result = await spaceDeleteBatchService(selectedSpaces.value);
            ElMessage({
                type:'success',
                message:'删除车位成功'
            })
            selectedSpaces.value=[]
            fetchSpaces();
        }catch(error){

        }
    }catch(error){
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
                <span>车位管理</span>

                <div class="extra">
                    <div style="display: flex; flex-direction:row;">
                        <span style="font-size: 20px;">请选择停车场:</span>
                        <el-select v-model="parkingId" placeholder="请选择停车场" @change="updateParkStorey" style="width:150px">
                            <el-option
                                v-for="p in parking"
                                :key="p.id"
                                :label="p.park_name"
                                :value="p.id">
                            </el-option>
                        </el-select>
                        <span style="margin-left: 20px;font-size: 20px;">请选择楼层:</span>
                        <el-select v-model="parkingStorey" placeholder="请选择楼层" @change="updateSpaces" style="width:150px">
                            <el-option
                                v-for="s in storeyOptions"
                                :key="s.id"
                                :label="s.storey"
                                :value="s.id">
                            </el-option>
                        </el-select>
                        <el-button type="primary" style="margin-left: 20px;" @click="addSpace"><el-icon><Plus /></el-icon>新增车位</el-button>
                    </div>
                </div>
            </div>
        </template >
        <!-- 搜索表单 -->
        <el-form  inline >
            <el-form-item label="车位名称:">
                <el-input v-model="selectSpaceName" placeholder="请输入车位名称" style="width:140px"></el-input>
            </el-form-item>
            <el-form-item >
                <el-button type="primary" @click="querySpaceList">搜索</el-button>
                <el-button @click="selectSpaceName=null">重置</el-button>
            </el-form-item>
        </el-form>
        <div class="parking-map">
            <canvas ref="parkingCanvas" @click="selectedParkingSpace"></canvas>
        </div>
        <div>
            <el-button type="primary" @click="updateSpace">修改</el-button>
            <el-button type="danger" @click="spaceDeleteBatch">删除</el-button>
        </div>
        <el-dialog v-model="addDialog" :title="title" width="40%" @close="addDialog=false">
            <el-form :model="spaceModel" label-width="120px" :rules="addRules" style="padding-right: 30px">
                <el-form-item label="停车场名称" prop="park_name">
                    <el-select v-model="spaceModel.park_name" placeholder="请选择停车场" @change="AddUpdateParkStorey" :disabled="title!=='添加车位'">
                        <el-option
                            v-for="p in addParking"
                            :key="p.id"
                            :label="p.park_name"
                            :value="p.park_name">
                        </el-option>
                    </el-select>
                </el-form-item>
                <el-form-item label="车位名称" prop="spaces_name">
                    <el-input v-model="spaceModel.spaces_name" placeholder="请输入车位名称"/>
                </el-form-item>
                <el-form-item label="车位所在列(x)" prop="spaces_col">
                    <el-input type="number" v-model="spaceModel.spaces_col" placeholder="请输入车位列" :disabled="title!=='添加车位'"/>
                </el-form-item>
                <el-form-item label="车位所在行(Y)" prop="spaces_row">
                    <el-input type="number" v-model="spaceModel.spaces_row" placeholder="请输入车位行" :disabled="title!=='添加车位'"/>
                </el-form-item>
                <el-form-item label="车位楼层" prop="spaces_storey">
                    <el-select v-model="spaceModel.spaces_storey" placeholder="请选择楼层" :disabled="title!=='添加车位'" >
                        <el-option
                            v-for="s in addStoreyOptions"
                            :key="s.id"
                            :label="s.storey"
                            :value="s.id">
                        </el-option>
                    </el-select>
                </el-form-item>
                <el-form-item label="车位状态" prop="spaces_flag">
                    <el-select v-model="spaceModel.spaces_flag" placeholder="请选择状态" >
                        <el-option
                            v-for="f in flag"
                            :key="f.id"
                            :label="f.name"
                            :value="f.id">
                        </el-option>
                    </el-select>
                </el-form-item>
                <el-form-item label="车位使用者"  v-show="spaceModel.spaces_flag==0||spaceModel.spaces_flag==1">
                    <el-input v-model="spaceModel.user_name" placeholder="请输入用户名"
                    :disabled="spaceModel.spaces_flag!=1" />
                </el-form-item>
            </el-form>
            <template #footer>
                <span class="dialog-footer">
                    <el-button @click="addDialog=false">取消</el-button>
                    <el-button type="primary" @click="title==='添加车位'?spaceAdd():spaceUpdate()"> 确认 </el-button>
                </span>
            </template>
        </el-dialog>
        <el-dialog v-model="listDialog" title="按名查询" width="60%" @close="listDialog=false">
            <el-table :data="querySpaces" height="420" stripe style="width: 100%">
                <el-table-column prop="id" label="车位id" width="90" />
                <el-table-column prop="spaces_name" label="车位名" width="100" />
                <el-table-column prop="park_name" label="停车场名" width="180" />
                <el-table-column prop="spaces_row" label="行" width="50" />
                <el-table-column prop="spaces_col" label="列" width="50" />
                <el-table-column prop="spaces_storey" label="楼层" width="80" />
                <el-table-column
                    prop="spaces_flag"
                    label="车位状态"
                    width="120"
                    :filters="[
                        { text: '空闲车位', value: 0 },
                        { text: '被占用车位', value: 1 },
                        { text: '道路', value: 2 },
                        { text: '墙壁', value: 3 },
                        { text: '出入口', value: 4 },
                    ]"
                    :filter-method="spaceFlagFilterTag"
                    filter-placement="bottom-end"
                >
                    <template #default="scope">
                        <el-tag
                            :type="scope.row.spaces_flag === 0 ?'success': (scope.row.spaces_flag === 1?'danger':(scope.row.spaces_flag===2?'info':(scope.row.spaces_flag === 3?'warning':'primary'))) "
                            disable-transitions
                        >{{ scope.row.spaces_flag === 0 ?'空闲车位': (scope.row.spaces_flag === 1?'被占用车位':(scope.row.spaces_flag===2?'道路':(scope.row.spaces_flag === 3?'墙壁':'出入口')))}}</el-tag>
                    </template>
                </el-table-column>
                <el-table-column prop="user_name" label="用户名" width="100" />
                <template #empty>
                <el-empty description="没有数据" />
            </template>
            </el-table>
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
.parking-map {
    width: 100%;
    overflow-x: auto;
  /* 添加你的样式 */
}


</style>
