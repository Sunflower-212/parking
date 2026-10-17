<script lang="ts" setup>
import {UploadFilled,Plus} from '@element-plus/icons-vue';
import { ref, onBeforeUnmount,onMounted,watchEffect } from 'vue';
import { ElMessage } from 'element-plus';
import useAdminInfoStore from '@/stores/adminInfo';
const adminInfoStore = useAdminInfoStore();

const enterIsCameraOpen = ref(false);
const exitIsCameraOpen = ref(false);
const enterVideoStream = ref(null);
const exitVideoStream = ref(null);
const enterDeviceId = ref('');
const exitDeviceId = ref('');
const enterVideoElement = ref<HTMLVideoElement | null>(null); // 进站口视频元素
const exitVideoElement = ref<HTMLVideoElement | null>(null); // 出站口视频元素
const enterCanvas = ref<HTMLCanvasElement | null>(null); // 进站口画布
const exitCanvas = ref<HTMLCanvasElement | null>(null); // 出站口画布
const enterPlateNum = ref('')
const exitPlateNum = ref('')
//摄像头信息列表
interface CameraDevice {
  deviceId: string;
  label: string;
}
const enterVideos= ref<CameraDevice[]>([])
const exitVideos= ref<CameraDevice[]>([])

const parkingId = ref('')
const parking = ref([
{
        id:'',
        park_name:"",
    }
])


import { adminParkingListService,parkingListService } from '@/api/parking';

const initialization = async()=>{
    if(adminInfoStore.info.permissions===0){
        let result = await adminParkingListService();
        parking.value = result.data;
    }
    else if(adminInfoStore.info.permissions===1){
        let result = await parkingListService();
        parking.value = result.data;
    }
    else {
        ElMessage({type:'warning', message:'系统异常：非管理员'})
    }
    if(parking.value.length===0){
        ElMessage.info("没有停车场，请先添加");
        return;
    }
    parkingId.value = parking.value[0].id
    // 获取所有媒体设备
    navigator.mediaDevices.enumerateDevices()
        .then(function(devices) {
            // 过滤出视频输入设备（摄像头）
            var videoInputs = devices.filter(function(device) {
                return device.kind === 'videoinput';
            });
            enterVideos.value = videoInputs;
            exitVideos.value = videoInputs;
        })
        .catch(function(err) {
            ElMessage.error("无法枚举设备:"+err)
        });
}
const enterCloseCamera = () => {
    if (enterVideoStream.value) {
        enterVideoStream.value.getTracks().forEach(track => track.stop());
        enterVideoStream.value = null;
        if(enterVideoElement.value){
            enterVideoElement.value.srcObject=null
        }
        enterIsCameraOpen.value = false;
    }
};
const exitCloseCamera = () => {
    if (exitVideoStream.value) {
        exitVideoStream.value.getTracks().forEach(track => track.stop());
        exitVideoStream.value = null;
        if(exitVideoElement.value){
            exitVideoElement.value.srcObject=null
        }
        exitIsCameraOpen.value = false;
    }
};

const enterToggleCamera = async () => {
    if(!enterDeviceId.value){
        ElMessage.error("请先选择摄像头");
        return;
    }
    if (enterIsCameraOpen.value) {
        enterCloseCamera();
    } else {
        try {
            enterVideoStream.value = await navigator.mediaDevices.getUserMedia({ video:  { deviceId: { exact: enterDeviceId.value } }  });
            if(enterVideoElement.value){
                enterVideoElement.value.srcObject=enterVideoStream.value;
            }

            enterIsCameraOpen.value = true;
        } catch (err) {
            console.error('摄像头开启失败：', err);
            ElMessage.error("摄像头开启失败："+err);
        }
    }
};

const exitToggleCamera = async () => {
    if(!exitDeviceId.value){
        ElMessage.error("请先选择摄像头");
        return;
    }
    if (exitIsCameraOpen.value) {
        exitCloseCamera();
    } else {
        try {
            exitVideoStream.value = await navigator.mediaDevices.getUserMedia({ video: { deviceId: { exact: exitDeviceId.value } }});
            if(exitVideoElement.value){
                exitVideoElement.value.srcObject=exitVideoStream.value;
            }

            exitIsCameraOpen.value = true;
        } catch (err) {
            console.error('摄像头开启失败：', err);
            ElMessage.error("摄像头开启失败："+err);
        }
    }
};

const enterTakePhoto = () => {
    if (!enterCanvas.value || !enterVideoElement.value) return;
    const ctx = enterCanvas.value.getContext('2d');
    ctx.drawImage(enterVideoElement.value, 0, 0, enterCanvas.value.width, enterCanvas.value.height);
    // ... 可以在这里添加图片处理或下载逻辑 ...
};

const exitTakePhoto = () => {
    if (!exitCanvas.value || !exitVideoElement.value) return;
    const ctx = exitCanvas.value.getContext('2d');
    ctx.drawImage(exitVideoElement.value, 0, 0, exitCanvas.value.width, exitCanvas.value.height);
    // ... 可以在这里添加图片处理或下载逻辑 ...
};

const clearEnterCanvas=()=>{
    if (enterCanvas.value) {
        const ctx = enterCanvas.value.getContext('2d');
        if (ctx) {
            // 清除整个 canvas
            ctx.clearRect(0, 0, enterCanvas.value.width, enterCanvas.value.height);
        }
    }
}

const clearExitCanvas=()=>{
    if (exitCanvas.value) {
        const ctx = exitCanvas.value.getContext('2d');
        if (ctx) {
            // 清除整个 canvas
            ctx.clearRect(0, 0, exitCanvas.value.width, exitCanvas.value.height);
        }
    }
}

//辅助函数：将 DataURL 转换为 Blob 对象
const dataURLtoBlob=(dataUrl)=> {
        const arr = dataUrl.split(','), mime = arr[0].match(/:(.*?);/)[1];
        const bstr = atob(arr[1]);
        let n = bstr.length;
        const u8arr = new Uint8Array(n);
        while (n--) {
            u8arr[n] = bstr.charCodeAt(n);
        }
        return new Blob([u8arr], { type: mime });
}
import {enterUploadService} from '@/api/enter.js'
const enterUploadImage = async()=>{
    enterPlateNum.value='';
    const canvas = enterCanvas.value;
    if (!canvas) return;

    // 将 Canvas 内容转换为 DataURL
    const dataUrl = canvas.toDataURL('image/png');
    const blob = dataURLtoBlob(dataUrl);

    // 使用 FormData 封装图片数据
    const formData = new FormData();
    formData.append('file', blob, 'uploaded_image.png');
    formData.append('park_id', parkingId.value);
    let result = await enterUploadService(formData);
    enterPlateNum.value = result.data
}
import {exitUploadService} from '@/api/orders.js'
const exitUploadImage = async()=>{
    exitPlateNum.value='';
    const canvas = exitCanvas.value;
    if (!canvas) return;

    // 将 Canvas 内容转换为 DataURL
    const dataUrl = canvas.toDataURL('image/png');
    const blob = dataURLtoBlob(dataUrl);

    // 使用 FormData 封装图片数据
    const formData = new FormData();
    formData.append('file', blob, 'uploaded_image.png');
    formData.append('park_id', parkingId.value);
    let result = await exitUploadService(formData);
    exitPlateNum.value = result.data
}
 // 监听permissions 的变化
watchEffect(() => {
    if (adminInfoStore.info.permissions !== null) {
        initialization();
    }
});

onBeforeUnmount(() => {
    enterCloseCamera();
    exitCloseCamera();
});
</script>

<template>
    <el-card class="page-container">
        <template #header>
            <div class="header">
                <span>进出站管理</span>
                <div>
                    <span>请选择停车场:</span>
                    <el-select v-model="parkingId" placeholder="请选择停车场" style="width:150px">
                        <el-option
                            v-for="p in parking"
                            :key="p.id"
                            :label="p.park_name"
                            :value="p.id">
                        </el-option>
                    </el-select>
                </div>
            </div>
        </template>
        <div class="video-container">
            <div class="enter-container" >
                <div>
                    <strong style="font-size: 24px;">进站口</strong>
                    <el-select v-model="enterDeviceId" placeholder="请选择进站摄像头" style="width:200px">
                        <el-option
                            v-for="env in enterVideos"
                            :key="env.deviceId"
                            :label="env.label"
                            :value="env.deviceId">
                        </el-option>
                    </el-select>
                </div>
                <video ref="enterVideoElement" autoplay style="margin-bottom: 10px;"></video>

                <div style="display: flex; flex-direction: row;">
                    <div style="display: flex; flex-direction: column;">
                        <div style="display: flex; flex-direction: row;">
                            <el-button :type="enterIsCameraOpen?'danger':'success'" @click="enterToggleCamera">
                                {{ enterIsCameraOpen ? '关闭摄像头' : '开启摄像头' }}
                            </el-button>
                            <el-button type="primary" @click="enterTakePhoto">拍照</el-button>
                        </div>
                        <div style="margin-top: 140px;">
                            <el-button :icon="UploadFilled" type="primary" @click="enterUploadImage" >上传</el-button>
                            <el-button type="info" @click="clearEnterCanvas">清除图片</el-button>
                        </div>
                    </div>
                    <canvas ref="enterCanvas" style="margin-left: 20px;"></canvas>
                </div>
                <span>识别的车牌号为:{{ enterPlateNum }}</span>
            </div>



            <el-divider direction="vertical" border-style="dashed" style="height: 100%;"/>
            <div class="exit-container" >
                <div>
                    <strong style="font-size: 24px;">出站口</strong>
                    <el-select v-model="exitDeviceId" placeholder="请选择出站摄像头" style="width:200px">
                        <el-option
                            v-for="exv in exitVideos"
                            :key="exv.deviceId"
                            :label="exv.label"
                            :value="exv.deviceId">
                        </el-option>
                    </el-select>
                </div>

                <video ref="exitVideoElement" autoplay style="margin-bottom: 10px;"></video>
                <div style="display: flex; flex-direction: row;">
                    <div style="display: flex; flex-direction: column;">
                        <div style="display: flex; flex-direction: row;">
                            <el-button :type="exitIsCameraOpen?'danger':'success'" @click="exitToggleCamera">
                                {{ exitIsCameraOpen ? '关闭摄像头' : '开启摄像头' }}
                            </el-button>
                            <el-button type="primary" @click="exitTakePhoto">拍照</el-button>
                        </div>
                        <div style="margin-top: 140px;">
                            <el-button :icon="UploadFilled" type="primary" @click="exitUploadImage" >上传</el-button>
                            <el-button type="info" @click="clearExitCanvas">清除图片</el-button>
                        </div>
                    </div>
                    <canvas ref="exitCanvas" style="margin-left: 20px;"></canvas>
                </div>
                <span>识别的车牌号为:{{ exitPlateNum }}</span>
            </div>
        </div>
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
    .video-container{
        display: flex;
        height: 750px;
        width: 100%;
        align-items: stretch;
        .enter-container{
            display: flex;
            flex: 1; /* 使两部分平均分配空间 */
            flex-direction: column;
            border: 1px solid black;
        }
        .enter-container video {
            height: 290px;
        }
        .enter-container canvas {
            height: 290px;
            width: 370px;
        }


        .exit-container{
            display: flex;
            flex: 1;
            flex-direction: column;
            border: 1px solid black;
        }
        .exit-container video{
            height: 290px;
        }
        .exit-container canvas {
            height: 290px;
            width: 370px;
        }
    }
}

.btnAndCanvas{
    display: flex; /* 创建一个 flex 容器 */
    flex-direction: row; /* 水平布局 */
    align-items: flex-start; /* 顶部对齐 */
}

</style>
