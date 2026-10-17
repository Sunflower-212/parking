import './assets/main.scss'

import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import router from '@/router'
import App from './App.vue'
import {createPinia} from 'pinia'
import { createPersistedState } from 'pinia-persistedstate-plugin'
import locale from 'element-plus/dist/locale/zh-cn.js'


/* 核心 */
import { library } from '@fortawesome/fontawesome-svg-core'
/* 引用图标库 FontAwesome Icon*/
import {FontAwesomeIcon} from "@fortawesome/vue-fontawesome";
/* 引用图标 */
import {   } from '@fortawesome/free-regular-svg-icons' //常规
import { faWallet,faArrowsSpin,faCar,faSquareParking,faSignIn,faDollarSign,faUserTie,faLock } from '@fortawesome/free-solid-svg-icons' //实体
import { faAlipay , faWeixin} from '@fortawesome/free-brands-svg-icons' //品牌
/* add icons to the library */
library.add(faWallet,faArrowsSpin,faCar,faSquareParking,faSignIn,faDollarSign,faUserTie,faLock,
    faAlipay,faWeixin)

const app=createApp(App);


const pinia = createPinia();
const persist = createPersistedState();
pinia.use(persist);
app.use(pinia);
app.use(router);
app.use(ElementPlus,{locale});
app.component('font-awesome-icon', FontAwesomeIcon)
app.mount('#app')
