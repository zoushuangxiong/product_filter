<template>
  <div class="app-container scan-page">
    <el-card shadow="never">
      <template #header><strong>商品内容检测</strong><span class="subtitle">标题、主图与详情图</span></template>
      <el-form label-position="top" @submit.prevent="start">
        <el-form-item label="商品平台（必选）">
          <el-select v-model="form.platform" placeholder="请选择淘宝/天猫或1688" :disabled="active || busy" style="width:260px" @change="changePlatform">
            <el-option label="淘宝 / 天猫" value="taobao"/><el-option label="1688" value="1688"/>
          </el-select>
          <span class="subtitle">先选平台，再导入文件；每个任务只检测一个平台。</span>
        </el-form-item>
        <el-row :gutter="22">
          <el-col :xs="24" :md="8">
            <el-form-item label="商品 CSV 文件">
              <div class="csv-import">
                <input ref="fileInput" type="file" accept=".csv" hidden @change="importFile"/>
                <el-button type="primary" plain :disabled="!form.platform || active || busy" @click="fileInput.click()">{{ importedName ? '重新导入 CSV' : '导入 CSV' }}</el-button>
                <el-button @click="downloadTemplate">下载 CSV 模板</el-button>
                <template v-if="importedName"><p class="csv-name">{{ importedName }}</p><el-tag type="success">已导入 {{ importedCount }} 条商品记录</el-tag></template>
                <p v-else class="muted">请先选择平台，再导入 CSV 文件。</p>
                <p class="muted">仅支持 CSV，每次 1–10,000 条商品记录（不含表头），文件不超过 20 MB。模板中“商品链接”列必填，可填写所选平台的完整商品链接或数字 ID，其他列选填。</p>
              </div>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8"><el-form-item label="标题过滤词"><el-input v-model="form.titleWords" type="textarea" :rows="7" :placeholder="'每行一个词，按回车换行。例如：\n微信\n加好友\n二维码'" :disabled="active || busy"/></el-form-item></el-col>
          <el-col :xs="24" :md="8"><el-form-item label="图片文字过滤词"><el-input v-model="form.imageWords" type="textarea" :rows="7" :placeholder="'每行一个词，按回车换行。例如：\n微信\n加好友\n二维码'" :disabled="active || busy"/></el-form-item><el-checkbox v-model="form.detectPhones" :disabled="active || busy">同时筛查 11 位手机号</el-checkbox></el-col>
        </el-row>
        <p class="filter-word-hint">标题和图片文字过滤词均按换行分隔：每行一个词，按回车换行；逗号、顿号、空格不会分隔词语。每份词库最多 500 个词，每词最多 100 字符，总计最多 30,000 字符。</p>
        <div class="toolbar"><el-button type="primary" :loading="busy" :disabled="active || !importedName" @click="start">开始检测</el-button></div>
      </el-form>
    </el-card>
    <el-card shadow="never" class="results">
      <template #header><strong>检测结果</strong></template>
      <div class="toolbar">
        <el-select :model-value="job?.id" placeholder="选择历史任务" style="width:330px" @change="load"><el-option v-for="task in tasks" :key="task.id" :value="task.id" :label="taskLabel(task)"/></el-select>
        <el-button @click="refresh">刷新</el-button><el-button :disabled="!active || job?.cancelRequested" @click="stop">停止任务</el-button>
      </div>
      <template v-if="job">
        <p>{{ label(job.state) }} · 商品 {{ completed }}/{{ job.products.length }} · 图片 {{ processedImages }}/{{ totalImages }}<span v-if="job.cancelRequested"> · 正在停止</span></p>
        <el-alert v-if="job.error" :title="errorText(job.error)" type="error" :closable="false"/>
        <div class="summary"><div>商品总数<strong>{{ job.products.length }}</strong></div><div>命中词库<strong>{{ job.products.filter(p => p.verdict === 'MATCHED').length }}</strong></div><div>待复核 / 未完成<strong>{{ job.products.filter(p => p.incomplete || p.state !== 'DONE').length }}</strong></div><div>符合导出条件<strong>{{ job.products.filter(eligible).length }}</strong></div></div>
        <div class="toolbar"><el-select v-model="filter" style="width:180px"><el-option label="全部结果" value="ALL"/><el-option label="命中词库" value="MATCHED"/><el-option label="待复核 / 未完成" value="REVIEW"/><el-option label="符合导出条件" value="ELIGIBLE"/></el-select><el-button @click="download(false)">导出全部 CSV</el-button><el-button type="primary" plain @click="download(true)">导出通过项 CSV</el-button></div>
        <el-table :data="products" row-key="itemId" class="result-table">
          <el-table-column label="商品" min-width="230"><template #default="{row}"><strong>{{ row.title || '等待获取标题' }}</strong><p class="muted">{{ row.itemId }} · {{ label(row.state) }}</p></template></el-table-column>
          <el-table-column label="图片进度" width="150"><template #default="{row}">主图 {{ row.pictures.filter(p => p.kind === 'MAIN').length }} 张<br>详情图 {{ row.pictures.filter(p => p.kind === 'DETAIL').length }} 张<br>已处理 {{ row.pictures.filter(p => ['DONE','FAILED'].includes(p.state)).length }}/{{ row.pictures.length }}</template></el-table-column>
          <el-table-column label="结果与证据" min-width="300"><template #default="{row}"><el-tag :type="row.verdict === 'MATCHED' ? 'danger' : 'info'">{{ label(row.verdict) }}</el-tag> <el-tag v-if="row.incomplete" type="warning">内容未完整检测</el-tag><p v-if="row.titleHits.length">标题命中：{{ row.titleHits.join('、') }}</p><p v-if="row.error" class="error">{{ errorText(row.error) }}</p><div class="evidence"><template v-for="(pic,index) in row.pictures" :key="index"><el-button v-if="pic.hits.length || hasLowConfidence(pic)" :type="pic.hits.length ? 'danger' : 'warning'" plain size="small" :disabled="!pic.ocr" @click="openPicture(row,index)">{{ pic.kind === 'MAIN' ? '主图' : '详情图' }} {{ pic.index }} · {{ pic.hits.length ? '命中' : '' }}{{ pic.hits.length && hasLowConfidence(pic) ? ' / ' : '' }}{{ hasLowConfidence(pic) ? '低置信度待核查' : '' }}</el-button></template></div><div class="scan-warnings"><p v-for="warning in visibleWarnings(row)" :key="warning">{{ warning }}</p><template v-for="(pic,index) in row.pictures" :key="index"><p v-if="pic.error">{{ pic.kind === 'MAIN' ? '主图' : '详情图' }} {{ pic.index }}：{{ errorText(pic.error) }}</p></template></div></template></el-table-column>
          <el-table-column label="人工复核" min-width="210"><template #default="{row}"><p>{{ label(row.review) }} {{ row.reviewNote }}</p><el-button size="small" :disabled="active || row.incomplete || row.state !== 'DONE'" @click="review(row,'TRUSTED')">信任</el-button><el-button size="small" :disabled="active" @click="review(row,'REJECTED')">排除</el-button><el-button size="small" :disabled="active || row.review === 'NONE'" @click="review(row,'NONE')">撤销</el-button></template></el-table-column>
        </el-table>
      </template><el-empty v-else description="还没有检测任务"/>
      <p class="muted">“未命中”只表示未命中本次词库，不代表平台合规。缺图、识别失败和低置信度内容不会自动计为通过。</p>
    </el-card>
    <el-dialog v-model="pictureVisible" :title="pictureTitle" width="90%" class="picture-dialog" append-to-body @closed="clearPicture">
      <div v-if="picture" class="picture-grid" v-loading="pictureLoading"><div class="preview-frame"><img v-if="previewUrl" :src="previewUrl" alt="商品核查图片"><svg v-if="previewUrl" :viewBox="`0 0 ${picture.ocr.width} ${picture.ocr.height}`"><template v-for="(hit,index) in hits" :key="index"><polygon :points="hit.points" fill="none" stroke="white" stroke-width="4" vector-effect="non-scaling-stroke"/><polygon :points="hit.points" fill="none" :stroke="hit.matched ? '#e11d2e' : '#c87500'" stroke-width="2" vector-effect="non-scaling-stroke"/></template></svg><template v-if="previewUrl"><span v-for="(hit,index) in hits" :key="index" class="hit-marker" :style="{left: hit.left+'%', top: hit.top+'%', background: hit.matched ? '#e11d2e' : '#c87500'}">{{ index+1 }}</span></template></div><div><p v-if="picture.hits.length">命中词：{{ picture.hits.join('、') }}</p><p v-if="hasLowConfidence(picture)" class="muted">橙框标记识别置信度低于 60% 的文字，请对照原图核查。</p><div v-for="(hit,index) in hits" :key="index" class="hit-line"><b :style="{background: hit.matched ? '#e11d2e' : '#c87500'}">{{ index+1 }}</b><div>{{ hit.text }}<div class="muted">{{ hit.matched ? '命中词库' : '待核查文字' }}<span v-if="hit.lowConfidence"> · 低置信度 {{ (hit.score*100).toFixed(1) }}%</span></div></div></div><p v-if="!hits.length">文字位置暂不可用，请结合原图复核。</p></div></div>
    </el-dialog>
    <el-dialog v-model="reviewVisible" :title="pendingReview?.decision === 'TRUSTED' ? '信任本商品' : '排除本商品'" width="500px" append-to-body><el-form label-position="top"><el-form-item label="复核原因（选填）"><el-input v-model="reviewNote" type="textarea" :rows="4" maxlength="500" placeholder="可留空"/></el-form-item></el-form><p class="muted">仅影响当前任务，保留原始词库和命中证据。</p><template #footer><el-button @click="reviewVisible=false">取消</el-button><el-button type="primary" :loading="reviewBusy" @click="saveReview">保存复核</el-button></template></el-dialog>
  </div>
</template>

<script setup name="ProductScan">
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { saveAs } from 'file-saver'
import * as api from '@/api/product/scan'
const form = reactive({platform:'', items:'', titleWords:'', imageWords:'', detectPhones:false})
const importedName=ref(''), importedCount=ref(0)
const tasks=ref([]), job=ref(null), filter=ref('ALL'), busy=ref(false), fileInput=ref(null)
let timer, disposed=false, requestSequence=0, sourceCsv=null
const labels={QUEUED:'排队中',RUNNING:'检测中',COMPLETED:'执行结束',CANCELLED:'已停止',INTERRUPTED:'服务重启中断',FAILED:'失败',PENDING:'待处理',FETCHING:'获取商品',SCANNING:'识别图片',DONE:'完成',MATCHED:'命中词库',REVIEW:'待复核',CLEAR:'未命中',NONE:'未复核',TRUSTED:'人工信任',REJECTED:'人工排除'}
const label=s=>labels[s]||s
const active=computed(()=>job.value && ['QUEUED','RUNNING'].includes(job.value.state))
const eligible=p=>p.state==='DONE'&&!p.incomplete&&p.review!=='REJECTED'&&(p.verdict==='CLEAR'||p.review==='TRUSTED')
const completed=computed(()=>job.value?.products.filter(p=>['DONE','FAILED','CANCELLED','INTERRUPTED'].includes(p.state)).length||0)
const totalImages=computed(()=>job.value?.products.reduce((n,p)=>n+p.pictures.length,0)||0)
const processedImages=computed(()=>job.value?.products.flatMap(p=>p.pictures).filter(p=>['DONE','FAILED'].includes(p.state)).length||0)
const products=computed(()=>(job.value?.products||[]).filter(p=>filter.value==='ALL'||filter.value==='MATCHED'&&p.verdict==='MATCHED'||filter.value==='REVIEW'&&(p.incomplete||p.state!=='DONE')||filter.value==='ELIGIBLE'&&eligible(p)))
const taskLabel=t=>`${new Date(t.createdAt).toLocaleString()} · ${t.rules?.platform==='1688'?'1688':'淘宝/天猫'} · ${t.products.length} 个 · ${label(t.state)}`
const errorMessages={
  INVALID_ITEM_ID:'商品编号格式不正确，请检查导入数据',
  MISSING_API_CREDENTIALS:'暂时无法获取商品信息',
  PROVIDER_ACCESS_DENIED:'暂时无法获取商品信息',
  PROVIDER_ERROR:'暂时无法获取商品信息',
  RATE_LIMITED:'暂时无法获取商品信息',
  ITEM_UNAVAILABLE:'暂时无法获取商品信息',
  INVALID_RESPONSE:'暂时无法获取商品信息',
  TIMEOUT:'暂时无法获取商品信息',
  NETWORK_ERROR:'暂时无法获取商品信息',
  HTTP_ERROR:'暂时无法获取商品信息',
  RESPONSE_TOO_LARGE:'暂时无法获取商品信息'
}
const errorText=value=>errorMessages[value]||(/^[A-Z][A-Z0-9_]*$/.test(value||'')?'检测失败，请联系管理员':value)
const error=e=>ElMessage.error(errorText(e?.message)||'操作失败，请稍后重试')
function poll(){clearTimeout(timer);if(active.value&&!disposed)timer=setTimeout(()=>load(job.value.id),1500)}
async function load(id){clearTimeout(timer);const seq=++requestSequence;try{const {data}=await api.getTask(id);if(disposed||seq!==requestSequence)return;job.value=data;poll()}catch(e){if(!disposed)error(e)}}
async function refresh(){try{const {data}=await api.listTasks();if(disposed)return;tasks.value=data;const id=job.value?.id||data[0]?.id;if(id)await load(id)}catch(e){error(e)}}
async function start(){
  if(busy.value || active.value)return
  if(!form.platform)return ElMessage.warning('请先选择商品平台')
  if(!form.items.trim()||!form.titleWords.trim()||(!form.imageWords.trim()&&!form.detectPhones))return ElMessage.warning('请填写商品和过滤词')
  if(!sourceCsv || !importedName.value)return ElMessage.warning('请先导入 CSV 文件')
  busy.value=true
  try{
    await ElMessageBox.confirm(
      `所选平台：${form.platform==='1688'?'1688':'淘宝 / 天猫'}；导入文件：${importedName.value}；商品记录：${importedCount.value} 条。请确认 CSV 中的商品均属于所选平台。平台不匹配可能导致查询失败并浪费接口调用次数，开始检测后会按套餐扣量。`,
      '请确认商品数据与平台匹配',
      {type:'warning',confirmButtonText:'确认匹配，开始检测',cancelButtonText:'返回检查',closeOnClickModal:false}
    )
  }catch{busy.value=false;return}
  if(disposed){busy.value=false;return}
  try{const {data}=await api.createTask({...form,items:form.items.split(/\r?\n/).map(s=>s.trim()).filter(Boolean),sourceCsv});job.value=data;localStorage.setItem('product-scan-rules',JSON.stringify({titleWords:form.titleWords,imageWords:form.imageWords,detectPhones:form.detectPhones}));await refresh();poll()}catch(e){error(e)}finally{busy.value=false}
}
async function stop(){try{const {data}=await api.cancelTask(job.value.id);job.value=data;poll()}catch(e){error(e)}}
function changePlatform(){if(sourceCsv!==null){sourceCsv=null;importedName.value='';importedCount.value=0;form.items='';ElMessage.info('平台已切换，请重新导入对应平台的文件')}}
function csvRows(text){const rows=[];let row=[],cell='',quoted=false;for(let i=0;i<text.length;i++){const c=text[i];if(c==='"'){if(quoted&&text[i+1]==='"'){cell+='"';i++}else quoted=!quoted}else if(c===','&&!quoted){row.push(cell);cell=''}else if((c==='\n'||c==='\r')&&!quoted){if(c==='\r'&&text[i+1]==='\n')i++;row.push(cell);rows.push(row);row=[];cell=''}else cell+=c}if(cell||row.length){row.push(cell);rows.push(row)}if(quoted)throw Error('CSV 引号未闭合');return rows}
async function importFile(event){
  const file=event.target.files[0],platform=form.platform
  try{
    if(!file)return
    if(!platform)throw Error('请先选择商品平台')
    if(!file.name.toLowerCase().endsWith('.csv'))throw Error('只支持 CSV 文件，不支持 TXT 或其他格式')
    if(file.size>20*1024*1024)throw Error('导入文件不能超过 20 MB')
    const original=(await file.text()).replace(/^\uFEFF/,'')
    if(form.platform!==platform)throw Error('平台已切换，请重新导入')
    const rows=csvRows(original)
    const col=rows[0]?.findIndex(c=>['商品ID','商品id','itemId','item_id','商品链接'].includes(c.trim()))??-1
    const records=(col>=0?rows.slice(1):rows).filter(row=>row.some(cell=>cell.trim()))
    const items=records.map(row=>(row[col>=0?col:0]||'').trim())
    if(items.some(item=>!item))throw Error('CSV 中存在缺少商品 ID 或链接的数据行')
    if(items.length<1||items.length>10000)throw Error('每次请导入 1–10,000 条商品记录')
    sourceCsv=original;form.items=items.join('\n');importedName.value=file.name;importedCount.value=items.length
    ElMessage.success('CSV 已导入')
  }catch(e){error(e)}finally{event.target.value=''}
}
function downloadTemplate(){
  const columns=['序号','关键词','主图','商品链接','类目','商品标题','店铺链接','价格','销量','评论数','状态']
  saveAs(new Blob(['\uFEFF'+columns.join(',')+'\r\n'],{type:'text/csv;charset=utf-8'}),'商品导入模板.csv')
}
async function download(only){try{saveAs(await api.exportTask(job.value.id,only),only?'商品筛查-通过项.csv':'商品筛查-全部.csv')}catch(e){error(e)}}
const reviewVisible=ref(false),reviewNote=ref(''),pendingReview=ref(null),reviewBusy=ref(false)
function review(p,decision){pendingReview.value={id:job.value.id,itemId:p.itemId,decision};reviewNote.value='';if(decision==='NONE')saveReview();else reviewVisible.value=true}
async function saveReview(){const r=pendingReview.value;if(!r||reviewBusy.value)return;reviewBusy.value=true;try{const {data}=await api.reviewProduct(r.id,r.itemId,{decision:r.decision,note:reviewNote.value});if(job.value?.id===r.id)job.value=data;reviewVisible.value=false;pendingReview.value=null}catch(e){error(e)}finally{reviewBusy.value=false}}
const pictureVisible=ref(false),picture=ref(null),pictureTitle=ref(''),previewUrl=ref(''),pictureLoading=ref(false)
let pictureSequence=0
const hasLowConfidence=pic=>(pic.ocr?.lines||[]).some(line=>line.score<0.6)
const visibleWarnings=p=>p.warnings.filter(w=>!w.includes('有低置信度文字')&&!w.startsWith('检测范围为接口实际返回内容'))
function matchedLine(pic,line){
  const norm=s=>String(s??'').normalize('NFKC').toLowerCase()
  return pic.hits.some(hit=>/^手机号:1[3-9][0-9]{9}$/.test(hit)
    ? Array.from(norm(line.text).matchAll(/(?<![0-9])1[3-9][0-9]{9}(?![0-9])/g),m=>m[0]).includes(hit.slice(4))
    : norm(line.text).includes(norm(hit)))
}
const hits=computed(()=>{
  const pic=picture.value
  if(!pic?.ocr)return []
  return pic.ocr.lines.map(line=>({...line,matched:matchedLine(pic,line),lowConfidence:line.score<0.6}))
    .filter(line=>line.matched||line.lowConfidence).map(line=>{
      const w=pic.ocr.width,h=pic.ocr.height,pad=w*.012,box=line.box||[]
      if(!box.length)return {...line,points:'',left:0,top:0}
      const l=Math.max(0,Math.min(...box.map(p=>p[0]))-pad),t=Math.max(0,Math.min(...box.map(p=>p[1]))-pad)
      const r=Math.min(w,Math.max(...box.map(p=>p[0]))+pad),b=Math.min(h,Math.max(...box.map(p=>p[1]))+pad)
      return {...line,points:[[l,t],[r,t],[r,b],[l,b]].map(p=>p.join(',')).join(' '),left:Math.min(92,l/w*100),top:t/h*100}
    })
})
function clearPicture(){pictureSequence++;if(previewUrl.value)URL.revokeObjectURL(previewUrl.value);previewUrl.value='';picture.value=null}
async function openPicture(p,index){clearPicture();const seq=pictureSequence;picture.value=p.pictures[index];pictureTitle.value=`${picture.value.kind==='MAIN'?'主图':'详情图'} ${picture.value.index} · ${p.title}`;pictureVisible.value=true;pictureLoading.value=true;try{const blob=await api.getPicture(job.value.id,p.itemId,index);if(seq===pictureSequence&&pictureVisible.value&&!disposed)previewUrl.value=URL.createObjectURL(blob)}catch(e){error(e)}finally{if(seq===pictureSequence)pictureLoading.value=false}}
onMounted(()=>{try{const saved=JSON.parse(localStorage.getItem('product-scan-rules')||'{}');form.titleWords=saved.titleWords||'';form.imageWords=saved.imageWords||'';form.detectPhones=!!saved.detectPhones}catch{}refresh()})
onBeforeUnmount(()=>{disposed=true;requestSequence++;clearTimeout(timer);clearPicture()})
</script>

<style scoped>
.filter-word-hint{color:#d93025;font-weight:600;font-size:13px;line-height:1.7;margin:8px 0 0}.csv-import{width:100%;padding:16px;border:1px dashed var(--el-border-color);border-radius:6px}.csv-name{overflow-wrap:anywhere}.results{margin-top:20px}.subtitle{margin-left:16px;color:var(--el-text-color-secondary);font-size:13px}.toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:12px 0}.muted{color:var(--el-text-color-secondary);font-size:13px;line-height:1.7}.summary{display:flex;gap:14px;margin:18px 0}.summary>div{flex:1;padding:16px;background:var(--el-fill-color-light);border-radius:6px}.summary strong{display:block;font-size:26px;margin-top:8px}.evidence{display:flex;flex-wrap:wrap;gap:7px;margin:10px 0}.evidence .el-button{margin-left:0}.result-table{margin-top:18px}.scan-warnings{margin-top:10px;color:var(--el-color-warning-dark-2)}.error{color:var(--el-color-danger)}.picture-grid{display:grid;grid-template-columns:1.2fr 1fr;gap:24px;min-height:180px}.preview-frame{position:relative;align-self:start}.preview-frame img{display:block;width:100%}.preview-frame svg{position:absolute;inset:0;width:100%;height:100%;pointer-events:none}.hit-marker{position:absolute;transform:translateY(calc(-100% - 4px));background:#e11d2e;color:white;border:2px solid white;border-radius:6px;padding:3px 7px;font-weight:bold;pointer-events:none}.hit-line{display:flex;align-items:center;gap:12px;background:#fff5f3;color:#303133;padding:14px;margin:10px 0;border-radius:8px}.hit-line b{background:#e11d2e;color:white;padding:4px 8px;border-radius:5px}@media(max-width:800px){.picture-grid{grid-template-columns:1fr}.summary{flex-wrap:wrap}.summary>div{min-width:40%}.subtitle{display:block;margin:8px 0}}
</style>
