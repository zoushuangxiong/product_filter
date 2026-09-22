<template>
  <div class="app-container scan-page">
    <el-card shadow="never" class="setup-card">
      <template #header><div class="card-heading"><div><h2>商品内容检测</h2><p>导入商品，设置过滤词，检查标题与商品图片</p></div><div class="header-tags"><el-tag type="info" effect="plain">标题 / 主图 / 详情图</el-tag><el-tag type="success" effect="plain">今日额度 {{ usage.used }} / {{ usage.limit }}</el-tag></div></div></template>
      <el-form label-position="top" @submit.prevent="start">
        <div class="setup-grid">
          <section class="import-section">
            <div class="section-heading"><span class="step-number">1</span><h3>导入商品</h3></div>
        <el-form-item label="商品平台（必选）">
          <el-select v-model="form.platform" placeholder="请选择淘宝/天猫或1688" :disabled="active || busy || libraryApplying" style="width:100%" @change="changePlatform">
            <el-option label="淘宝 / 天猫" value="taobao"/><el-option label="1688" value="1688"/>
          </el-select>

        </el-form-item>
            <el-form-item label="商品 CSV 文件">
              <div class="csv-import">
                <el-icon class="upload-icon"><UploadFilled /></el-icon>
                <strong class="upload-title">导入待检测商品</strong>
                <input ref="fileInput" type="file" accept=".csv,.xlsx" hidden @change="importFile"/>
                <el-button type="primary" plain :disabled="!form.platform || active || busy" @click="fileInput.click()">{{ importedName ? '重新导入文件' : '导入 CSV / Excel' }}</el-button>
                <el-button link type="primary" @click="downloadTemplate">下载 CSV 模板</el-button>
                <template v-if="importedName"><p class="csv-name">{{ importedName }}</p><el-tag type="success">已导入 {{ importedCount }} 条商品记录</el-tag></template>
                <p v-else class="muted">请先选择平台，再导入 CSV 或 Excel 文件。</p>
                <p class="upload-limit">支持 CSV、Excel（.xlsx）· 最多 10,000 条 · 不超过 20 MB</p>
              </div>
            </el-form-item>

            <p class="muted import-note">每个任务只检测一个平台。CSV/Excel 必须包含表头，其中“商品链接”或“商品ID”列必填，支持完整链接或数字 ID，其他列选填。</p>
          </section>
          <section class="rules-section">
            <div class="section-heading"><span class="step-number">2</span><h3>设置过滤规则</h3></div>
            <el-form-item label="快速选择过滤词库" class="library-picker">
              <div class="library-controls">
              <el-select v-model="selectedLibrary" filterable clearable placeholder="选择已保存的词库（可选）" :loading="librariesLoading" :disabled="active || busy || libraryApplying" class="library-select" @change="applyLibrary">
                <el-option v-for="item in libraries" :key="item.id" :label="item.name" :value="item.id"/>
              </el-select>
              <el-button :loading="librariesLoading" :disabled="active || busy || libraryApplying" icon="Refresh" @click="loadLibraries">刷新词库</el-button>
              <el-button v-hasPermi="['product:wordLibrary:list']" link type="primary" @click="$router.push('/word-library')">管理词库</el-button>
              </div>
              <p class="muted">选择后自动填入规则，临时修改仅用于本次检测。清空选择会保留当前规则。</p>
              <p v-if="librariesError" class="error">词库加载失败，请点击“刷新词库”重试；也可手动填写过滤词。</p>
            </el-form-item>
            <el-row :gutter="22">
              <el-col :xs="24" :sm="12"><el-form-item label="标题过滤词"><el-input v-model="form.titleWords" type="textarea" :rows="6" :placeholder="'每行一个词，按回车换行。例如：\n微信\n加好友\n二维码'" :disabled="active || busy || libraryApplying"/></el-form-item></el-col>
              <el-col :xs="24" :sm="12"><el-form-item label="图片文字过滤词"><el-input v-model="form.imageWords" type="textarea" :rows="6" :placeholder="'每行一个词，按回车换行。例如：\n微信\n加好友\n二维码'" :disabled="active || busy || libraryApplying"/></el-form-item><div class="rule-checks"><el-checkbox v-model="form.detectPhones" :disabled="active || busy || libraryApplying">同时筛查 11 位手机号</el-checkbox><el-checkbox v-model="form.detectQrCodes" :disabled="active || busy || libraryApplying">检测二维码</el-checkbox></div></el-col>
            </el-row>
            <div class="filter-word-hint"><strong>填写规则</strong><span>每行一个词，按回车换行；逗号、顿号、空格不会分隔词语。标题过滤词最多 10,000 个，图片文字过滤词最多 10,000 个，每个词最多 100 字符，不限制词库总字符数。</span></div>
          </section>
        </div>
        <div class="scan-actions">
          <div class="action-status"><el-icon><Document /></el-icon><span v-if="importedName">已导入 <strong>{{ importedCount }}</strong> 条商品记录</span><span v-else>选择平台并导入 CSV 或 Excel 后，即可开始检测</span></div>
          <el-button type="primary" size="large" icon="VideoPlay" :loading="busy" :disabled="active || libraryApplying || !importedName" @click="start">开始检测</el-button>
        </div>
      </el-form>
    </el-card>
    <el-card shadow="never" class="results" v-loading="resultsLoading || historyLoading" element-loading-text="正在加载检测结果…">
      <template #header><div class="card-heading"><div><h2>检测结果</h2><p>查看命中内容、人工复核与导出结果</p></div></div></template>
      <div class="toolbar">
        <el-select :model-value="job?.id" :disabled="resultsLoading || historyLoading" placeholder="选择历史任务" style="width:330px" @change="load"><el-option v-for="task in tasks" :key="task.id" :value="task.id" :label="taskLabel(task)"/></el-select>
        <el-button :loading="historyLoading" :disabled="resultsLoading" @click="refresh">刷新</el-button><el-button :disabled="!active || job?.cancelRequested" @click="stop">停止任务</el-button>
      </div>
      <el-alert v-if="resultsError" :title="resultsError" type="error" :closable="false" show-icon/>
      <template v-if="job">
        <div class="task-progress" aria-live="polite">
          <div class="progress-heading"><strong>{{ progress.title }}</strong><span>已处理 {{ progress.processed }} / {{ pageStats.productCount }} 个商品</span></div>
          <el-progress :percentage="progress.percent" :stroke-width="10" :color="active ? '#409eff' : '#909399'"/>
          <div class="progress-details">
            <span v-if="progress.current">商品 ID：{{ progress.current.itemId }} · {{ label(progress.current.state) }}<template v-if="progress.current.state === 'SCANNING'"> · 图片已处理 {{ pageStats.currentProcessedImages }} / {{ pageStats.currentImages }} 张</template></span>
            <span v-else>{{ label(job.state) }} · 图片已处理 {{ processedImages }} / {{ totalImages }} 张</span><span v-if="job.startedAt">执行耗时：{{ formatDuration(job) }}</span>
            <span v-if="progress.failed">{{ progress.failed }} 个商品处理失败</span>
            <span v-if="job.cancelRequested && active">正在停止，请稍候…</span>
          </div>
        </div>
        <el-alert v-if="job.error" :title="errorText(job.error)" type="error" :closable="false"/>
        <div class="summary"><div>商品总数<strong>{{ pageStats.productCount }}</strong></div><div class="summary-matched">命中词库<strong>{{ pageStats.matched }}</strong></div><div class="summary-review">待复核<strong>{{ pageStats.review }}</strong></div><div class="summary-incomplete">未完成<strong>{{ pageStats.incomplete }}</strong></div><div class="summary-provider-error">商品信息获取异常<strong>{{ pageStats.providerError }}</strong></div><div class="summary-passed">已通过<strong>{{ pageStats.passed }}</strong></div></div>
        <div class="toolbar"><el-select v-model="filter" style="width:180px"><el-option label="全部结果" value="ALL"/><el-option label="命中词库" value="MATCHED"/><el-option label="待复核" value="REVIEW"/><el-option label="未完成" value="INCOMPLETE"/><el-option label="商品信息获取异常" value="PROVIDER_ERROR"/><el-option label="通过（可导出）" value="ELIGIBLE"/></el-select><el-button @click="download(false)">导出全部 CSV</el-button><el-button type="primary" plain @click="download(true)">导出通过项 CSV</el-button></div>
        <div class="confidence-controls">
          <label for="confidence-threshold">置信度阈值</label>
          <el-select id="confidence-threshold" :model-value="confidenceThreshold" :disabled="active || thresholdSaving || resultsLoading || busy" :loading="thresholdSaving" style="width:150px" aria-label="置信度阈值" @change="changeConfidenceThreshold">
            <el-option label="高（60%）" :value="0.6"/>
            <el-option label="中（50%）" :value="0.5"/>
            <el-option label="低（40%）" :value="0.4"/>
          </el-select>
          <span>低于 {{ Math.round(confidenceThreshold * 100) }}% 的文字需要复核；切换后更新当前任务结果及导出资格。检测期间不可修改。</span>
        </div>
        <el-table :data="pagedProducts" row-key="itemId" class="result-table">
          <el-table-column label="商品" min-width="230"><template #default="{row}"><el-link :href="productUrl(row)" target="_blank" rel="noopener noreferrer" type="primary" class="product-title"><strong>{{ row.title || '等待获取标题' }}</strong></el-link><p class="muted">{{ row.itemId }} · {{ label(row.state) }}</p></template></el-table-column>
          <el-table-column label="图片进度" width="150"><template #default="{row}">主图 {{ row.pictures.filter(p => p.kind === 'MAIN').length }} 张<br>详情图 {{ row.pictures.filter(p => p.kind === 'DETAIL').length }} 张<br>已处理 {{ row.pictures.filter(p => ['DONE','FAILED'].includes(p.state)).length }}/{{ row.pictures.length }}</template></el-table-column>
          <el-table-column label="结果与证据" min-width="300"><template #default="{row}"><el-tag :type="resultStatus(row).type">{{ resultStatus(row).text }}</el-tag><el-tag v-if="row.incomplete" type="warning">内容未完整检测</el-tag><div v-if="providerError(row) && row.retryCount < 1" class="retry-action"><el-button class="retry-button" type="danger" :disabled="active || retryBusy" @click.stop="retry(row)">重新获取商品信息</el-button></div><p v-if="row.titleHits.length">标题命中：{{ row.titleHits.join('、') }}</p><p v-if="row.error && !providerError(row)" class="error">{{ errorText(row.error) }}</p><div class="evidence"><template v-for="(pic,index) in row.pictures" :key="index"><el-button v-if="pic.error || pic.hits.length || hasLowConfidence(pic) || pic.qrCodes" :type="!pic.error && (pic.hits.length || pic.qrCodes) ? 'danger' : 'warning'" :class="{ 'picture-failed': pic.error }" plain size="small" @click="openPicture(row,index)">{{ pic.kind === 'MAIN' ? '主图' : '详情图' }} {{ pic.index }} · {{ pic.error ? '读取或识别失败' : '' }}{{ pic.qrCodes ? `二维码 ${pic.qrCodes} 个` : '' }}{{ pic.qrCodes && (pic.hits.length || hasLowConfidence(pic)) ? ' / ' : '' }}{{ pic.hits.length ? '命中' : '' }}{{ pic.hits.length && hasLowConfidence(pic) ? ' / ' : '' }}{{ hasLowConfidence(pic) ? '低置信度待核查' : '' }}</el-button></template></div><div class="scan-warnings"><p v-for="warning in visibleWarnings(row)" :key="warning">{{ warning }}</p></div></template></el-table-column>
          <el-table-column label="人工复核" min-width="210"><template #default="{row}"><p>{{ row.review === 'NONE' && eligible(row) ? '无需复核' : label(row.review) }} {{ row.reviewNote }}</p><el-tooltip v-if="!eligible(row) && row.review !== 'TRUSTED'" :content="trustDisabledReason(row)" :disabled="!trustDisabledReason(row)" placement="top"><span class="review-action"><el-button size="small" :disabled="!!trustDisabledReason(row)" @click="review(row,'TRUSTED')">信任</el-button></span></el-tooltip><el-button v-if="row.review !== 'REJECTED'" size="small" :disabled="active" @click="review(row,'REJECTED')">排除</el-button><el-button v-if="row.review !== 'NONE'" size="small" :disabled="active" @click="review(row,'NONE')">撤销</el-button></template></el-table-column>
        </el-table>
        <pagination v-show="pageStats.total > 0" class="result-pagination" :total="pageStats.total" v-model:page="resultPage" v-model:limit="resultPageSize" :page-sizes="[10, 20, 50, 100]" :auto-scroll="false" @pagination="reloadPage"/>

      </template><el-empty v-else-if="!resultsLoading && !historyLoading && !resultsError" description="还没有检测任务"/><div v-else-if="!job" class="results-placeholder"/>
      <p class="muted">“通过”表示符合本次检测规则，可导出通过项，不代表平台合规认证。识别失败、低置信度和未完成的商品不会自动通过。</p>
    </el-card>
    <el-dialog v-model="pictureVisible" :title="pictureTitle" width="90%" class="picture-dialog" append-to-body @closed="clearPicture">
      <div v-if="picture" class="picture-grid" v-loading="pictureLoading"><div class="preview-frame"><img v-if="previewUrl && !previewFailed" :src="previewUrl" alt="商品核查图片" referrerpolicy="no-referrer" @error="previewFailed=true"><el-empty v-if="previewFailed" description="原图暂时无法加载，请稍后再试"/><svg v-if="previewUrl && !previewFailed && picture.ocr" :viewBox="`0 0 ${picture.ocr.width} ${picture.ocr.height}`"><template v-for="(hit,index) in hits" :key="index"><polygon :points="hit.points" fill="none" stroke="white" stroke-width="4" vector-effect="non-scaling-stroke"/><polygon :points="hit.points" fill="none" :stroke="hit.matched ? '#e11d2e' : '#c87500'" stroke-width="2" vector-effect="non-scaling-stroke"/></template></svg><template v-if="previewUrl"><span v-for="(hit,index) in hits" :key="index" class="hit-marker" :style="{left: hit.left+'%', top: hit.top+'%', background: hit.matched ? '#e11d2e' : '#c87500'}">{{ index+1 }}</span></template></div><div><p v-if="picture.error" class="error">图片读取或识别失败，请对照原图人工核查。</p><p v-if="picture.hits.length">命中词：{{ picture.hits.join('、') }}</p><p v-if="hasLowConfidence(picture)" class="muted">橙框标记识别置信度低于 {{ Math.round(confidenceThreshold * 100) }}% 的文字，请对照原图核查。</p><div v-for="(hit,index) in hits" :key="index" class="hit-line"><b :style="{background: hit.matched ? '#e11d2e' : '#c87500'}">{{ index+1 }}</b><div>{{ hit.text }}<div class="muted">{{ hit.matched ? '命中词库' : '待核查文字' }}<span v-if="hit.lowConfidence"> · 低置信度 {{ (hit.score*100).toFixed(1) }}%</span></div></div></div><p v-if="!hits.length">文字位置暂不可用，请结合原图复核。</p></div></div>
    </el-dialog>
    <el-dialog v-model="reviewVisible" :title="pendingReview?.decision === 'TRUSTED' ? '信任本商品' : '排除本商品'" width="500px" append-to-body><el-form label-position="top"><el-form-item label="复核原因（选填）"><el-input v-model="reviewNote" type="textarea" :rows="4" maxlength="500" placeholder="可留空"/></el-form-item></el-form><p class="muted">仅影响当前任务，保留原始词库和命中证据。</p><template #footer><el-button @click="reviewVisible=false">取消</el-button><el-button type="primary" :loading="reviewBusy" @click="saveReview">保存复核</el-button></template></el-dialog>
  </div>
</template>

<script setup name="ProductScan">
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import * as XLSX from 'xlsx'
import { ElMessage, ElMessageBox } from 'element-plus'
import { saveAs } from 'file-saver'
import { UploadFilled, Document } from '@element-plus/icons-vue'
import * as api from '@/api/product/scan'
import { libraryOptions, getLibrary } from '@/api/product/wordLibrary'
const form = reactive({platform:'', items:'', titleWords:'', imageWords:'', detectPhones:false, detectQrCodes:false})
const usage = reactive({ used: '—', limit: 20000 })
let usageTimer, usageLoading = false
const libraries = ref([]), selectedLibrary = ref(null), librariesLoading = ref(false), libraryApplying = ref(false), librariesError = ref(false)
async function loadUsage() {
  if (usageLoading) return
  usageLoading = true
  try { Object.assign(usage, (await api.getUsage()).data) }
  catch { usage.used = '—' }
  finally { usageLoading = false }
}

async function loadLibraries() {
  if (librariesLoading.value) return
  librariesLoading.value = true
  librariesError.value = false
  try { libraries.value = (await libraryOptions()).data }
  catch { librariesError.value = true }
  finally { librariesLoading.value = false }
}
async function applyLibrary(id) {
  if (!id) return
  libraryApplying.value = true
  try {
    const { data } = await getLibrary(id)
    // 只复制规则正文，临时编辑不会写回词库，也不会改变历史任务。
    form.titleWords = data.titleWords
    form.imageWords = data.imageWords
    form.detectPhones = data.detectPhones
    form.detectQrCodes = data.detectQrCodes
  } catch { selectedLibrary.value = null }
  finally { libraryApplying.value = false }
}

const importedName=ref(''), importedCount=ref(0)
const tasks=ref([]), job=ref(null), filter=ref('ALL'), busy=ref(false), fileInput=ref(null), retryBusy=ref(false)
const resultsLoading=ref(true), historyLoading=ref(false), resultsError=ref('')
let timer, disposed=false, requestSequence=0, sourceCsv=null
const labels={QUEUED:'排队中',RUNNING:'检测中',COMPLETED:'执行结束',CANCELLED:'已停止',INTERRUPTED:'服务重启中断',FAILED:'失败',PENDING:'待处理',FETCHING:'获取商品',SCANNING:'识别图片',DONE:'完成',MATCHED:'命中词库',REVIEW:'待复核',CLEAR:'未命中',NONE:'未复核',TRUSTED:'人工信任',REJECTED:'人工排除'}
const label=s=>labels[s]||s
const productUrl=p=>p.platform==='1688'
  ? `https://detail.1688.com/offer/${encodeURIComponent(p.itemId)}.html`
  : `https://item.taobao.com/item.htm?id=${encodeURIComponent(p.itemId)}`
const active=computed(()=>job.value && ['QUEUED','RUNNING'].includes(job.value.state))
const eligible=p=>p.state==='DONE'&&!p.incomplete&&p.review!=='REJECTED'&&(p.verdict==='CLEAR'||p.review==='TRUSTED')
// 页面结果与通过项导出使用同一判定，避免把尚未完成或已排除的商品显示为通过。
function resultStatus(p){
  if(providerError(p))return {text:'商品信息获取异常',type:'danger'}
  if(eligible(p))return {text:p.review==='TRUSTED'?'通过（人工确认）':'通过',type:'success'}
  if(p.review==='REJECTED')return {text:'已排除',type:'danger'}
  if(['PENDING','FETCHING','SCANNING'].includes(p.state))return {text:label(p.state),type:'info'}
  if(p.state!=='DONE')return {text:'未完成',type:'warning'}
  if(p.incomplete)return {text:'待复核',type:'warning'}
  return {text:label(p.verdict),type:p.verdict==='MATCHED'?'danger':'warning'}
}
function trustDisabledReason(p){
  if(active.value)return '请等待当前任务结束后再复核'
  if(p.state!=='DONE')return '商品检测未成功完成，暂不能信任为通过'
  if(p.incomplete)return '存在低置信度文字、图片识别失败或未检测内容，当前规则不允许直接信任为通过'
  return ''
}
// 后端统计整个任务，当前页之外的商品也计入进度和汇总。
const pageStats = ref({ productCount: 0, total: 0, processed: 0, failed: 0 })
const progress = computed(() => {
  const stats = pageStats.value
  const current = active.value && stats.currentIndex ? { itemId: stats.currentItemId, state: stats.currentState } : null
  let title = label(job.value?.state) || '检测进度'
  if (job.value?.state === 'QUEUED') title = '等待开始检测'
  else if (current) title = `正在检测第 ${stats.currentIndex} 个商品，共 ${stats.productCount} 个`
  else if (job.value?.state === 'RUNNING') title = stats.processed === stats.productCount ? '正在汇总检测结果' : '正在准备检测下一个商品'
  else if (job.value?.state === 'COMPLETED') title = '检测已结束'
  return { title, current, processed: stats.processed, failed: stats.failed, percent: stats.productCount ? Math.floor(stats.processed * 100 / stats.productCount) : 0 }
})
const totalImages=computed(()=>pageStats.value.totalImages || 0)
const processedImages=computed(()=>pageStats.value.processedImages || 0)
const providerErrorCodes=['MISSING_API_CREDENTIALS','PROVIDER_ACCESS_DENIED','PROVIDER_ERROR','RATE_LIMITED','ITEM_UNAVAILABLE','INVALID_RESPONSE','TIMEOUT','NETWORK_ERROR','HTTP_ERROR','RESPONSE_TOO_LARGE']
const providerError=p=>providerErrorCodes.includes(p.error)
const resultPage = ref(1), resultPageSize = ref(50)
const pagedProducts = computed(() => job.value?.products || [])
function reloadPage() { if (job.value) return load(job.value.id) }
watch(filter, () => { resultPage.value = 1; reloadPage() })

const taskLabel=t=>`${new Date(t.createdAt).toLocaleString()} · ${(t.platform||t.rules?.platform)==='1688'?'1688':'淘宝/天猫'} · ${t.productCount??t.products?.length??0} 个 · ${label(t.state)}`
const formatDuration=task=>{
  if(!task?.startedAt)return '未开始'
  const end=task.completedAt?Date.parse(task.completedAt):Date.now()
  const seconds=Math.max(0,Math.floor((end-Date.parse(task.startedAt))/1000))
  return `${Math.floor(seconds/60)}分${String(seconds%60).padStart(2,'0')}秒`
}
const errorMessages={
  DAILY_LIMIT_EXCEEDED:'今日检测额度已用完',
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
async function retry(row){
  if(!job.value||retryBusy.value)return
  retryBusy.value=true
  try{ await api.retryProduct(job.value.id,row.itemId); ElMessage.success('已重新发起商品信息获取'); await load(job.value.id) }
  catch(e){ error(e) }
  finally{ retryBusy.value=false }
}
function poll(){clearTimeout(timer);if(active.value&&!disposed)timer=setTimeout(()=>load(job.value.id,false),1500)}
async function load(id,showLoading=true){
  loadUsage()
  clearTimeout(timer)
  if(job.value?.id !== id) resultPage.value=1
  const seq=++requestSequence
  if(showLoading)resultsLoading.value=true
  resultsError.value=''
  try{
    const {data}=await api.getTask(id,{pageNum:resultPage.value,pageSize:resultPageSize.value,filter:filter.value})
    if(disposed||seq!==requestSequence)return
    const {job:currentJob,...stats}=data
    job.value=currentJob;pageStats.value=stats;resultPage.value=data.pageNum;confidenceThreshold.value=currentJob.rules?.confidenceThreshold ?? 0.6;poll()
  }catch(e){
    if(!disposed&&seq===requestSequence){resultsError.value='检测结果加载失败，请点击刷新重试';error(e)}
  }finally{if(!disposed&&seq===requestSequence)resultsLoading.value=false}
}
async function refresh(){
  if(historyLoading.value)return
  historyLoading.value=true;resultsError.value=''
  try{
    const {data}=await api.listTasks()
    if(disposed)return
    tasks.value=data
    const id=job.value?.id||data[0]?.id
    if(id)await load(id)
    else resultsLoading.value=false
  }catch(e){if(!disposed){resultsError.value='历史任务加载失败，请点击刷新重试';resultsLoading.value=false;error(e)}}
  finally{if(!disposed)historyLoading.value=false}
}
async function start(){
  if (libraryApplying.value) return
  if(busy.value || active.value)return
  if(!form.platform)return ElMessage.warning('请先选择商品平台')
  if(!form.items.trim()||!form.titleWords.trim()||(!form.imageWords.trim()&&!form.detectPhones&&!form.detectQrCodes))return ElMessage.warning('请填写商品和过滤词，或开启手机号/二维码检测')
  if(!sourceCsv || !importedName.value)return ElMessage.warning('请先导入 CSV 或 Excel 文件')
  busy.value=true
  try{
    await ElMessageBox.confirm(
      `所选平台：${form.platform==='1688'?'1688':'淘宝 / 天猫'}；导入文件：${importedName.value}；商品记录：${importedCount.value} 条。请确认 CSV 中的商品均属于所选平台。平台不匹配可能导致查询失败并浪费接口调用次数，开始检测后会按套餐扣量。`,
      '请确认商品数据与平台匹配',
      {type:'warning',confirmButtonText:'确认匹配，开始检测',cancelButtonText:'返回检查',closeOnClickModal:false}
    )
  }catch{busy.value=false;return}
  if(disposed){busy.value=false;return}
  try{const {data}=await api.createTask({...form,confidenceThreshold:confidenceThreshold.value,items:form.items.split(/\r?\n/).map(s=>s.trim()).filter(Boolean),sourceCsv});await load(data.id);localStorage.setItem('product-scan-rules',JSON.stringify({titleWords:form.titleWords,imageWords:form.imageWords,detectPhones:form.detectPhones,detectQrCodes:form.detectQrCodes}));await refresh();poll()}catch(e){error(e)}finally{busy.value=false}
}
async function stop(){try{const id=job.value.id;await api.cancelTask(id);await load(id)}catch(e){error(e)}}
function changePlatform(){if(sourceCsv!==null){sourceCsv=null;importedName.value='';importedCount.value=0;form.items='';ElMessage.info('平台已切换，请重新导入对应平台的文件')}}
function csvRows(text){const rows=[];let row=[],cell='',quoted=false;for(let i=0;i<text.length;i++){const c=text[i];if(c==='"'){if(quoted&&text[i+1]==='"'){cell+='"';i++}else quoted=!quoted}else if(c===','&&!quoted){row.push(cell);cell=''}else if((c==='\n'||c==='\r')&&!quoted){if(c==='\r'&&text[i+1]==='\n')i++;row.push(cell);rows.push(row);row=[];cell=''}else cell+=c}if(cell||row.length){row.push(cell);rows.push(row)}if(quoted)throw Error('CSV 引号未闭合');return rows}
// Windows 导出的 CSV 常用 GBK；严格解码 UTF-8，失败后再尝试兼容 GBK 的 GB18030。
function decodeCsv(buffer){
  try{return new TextDecoder('utf-8',{fatal:true}).decode(buffer)}catch{
    try{return new TextDecoder('gb18030',{fatal:true}).decode(buffer)}catch{
      throw Error('CSV 编码无法识别，请保存为 UTF-8 或 GBK 编码后重新导入')
    }
  }
}
async function importFile(event){
  const file=event.target.files[0],platform=form.platform
  try{
    if(!file)return
    if(!platform)throw Error('请先选择商品平台')
    const lowerName=file.name.toLowerCase(), isCsv=lowerName.endsWith('.csv'), isXlsx=lowerName.endsWith('.xlsx')
    if(!isCsv&&!isXlsx)throw Error('只支持 CSV 或 Excel（.xlsx）文件')
    if(file.size>20*1024*1024)throw Error('导入文件不能超过 20 MB')
    let original, rows
    if(isXlsx){
      const workbook=XLSX.read(await file.arrayBuffer(),{type:'array',cellDates:false})
      const sheet=workbook.Sheets[workbook.SheetNames[0]]
      if(!sheet)throw Error('Excel 文件没有可读取的工作表')
      rows=XLSX.utils.sheet_to_json(sheet,{header:1,defval:'',raw:false}).map(row=>row.map(cell=>String(cell ?? '').trim()))
      original=XLSX.utils.sheet_to_csv(sheet,{FS:',',RS:'\r\n'})
    }else{
      original=decodeCsv(await file.arrayBuffer()).replace(/^\uFEFF/,'')
      rows=csvRows(original)
    }
    if(form.platform!==platform)throw Error('平台已切换，请重新导入')
    const col=rows[0]?.findIndex(c=>['商品ID','商品id','itemId','item_id','商品链接','商品链接（必填）'].includes(c.trim()))??-1
    if(col<0)throw Error('文件必须包含表头，并包含“商品链接”或“商品ID”列')
    const records=rows.slice(1).filter(row=>row.some(cell=>cell.trim()))
    const items=records.map(row=>(row[col>=0?col:0]||'').trim())
    if(items.some(item=>!item))throw Error('CSV 中存在缺少商品 ID 或链接的数据行')
    if(items.length<1||items.length>10000)throw Error('每次请导入 1–10,000 条商品记录')
    sourceCsv=original;form.items=items.join('\n');importedName.value=file.name;importedCount.value=items.length
    ElMessage.success(`${isXlsx ? 'Excel' : 'CSV'} 已导入`)
  }catch(e){error(e)}finally{event.target.value=''}
}
function downloadTemplate(){
  const columns=['序号','关键词','主图','商品链接','类目','商品标题','店铺链接','价格','销量','评论数','状态']
  saveAs(new Blob(['\uFEFF'+columns.join(',')+'\r\n'],{type:'text/csv;charset=utf-8'}),'商品导入模板.csv')
}
async function download(only){if(thresholdSaving.value)return ElMessage.warning('正在更新检测结果，请稍后导出');try{saveAs(await api.exportTask(job.value.id,only),only?'商品筛查-通过项.csv':'商品筛查-全部.csv')}catch(e){error(e)}}
const reviewVisible=ref(false),reviewNote=ref(''),pendingReview=ref(null),reviewBusy=ref(false)
function review(p,decision){pendingReview.value={id:job.value.id,itemId:p.itemId,decision};reviewNote.value='';if(decision==='NONE')saveReview();else reviewVisible.value=true}
async function saveReview(){const r=pendingReview.value;if(!r||reviewBusy.value)return;reviewBusy.value=true;try{await api.reviewProduct(r.id,r.itemId,{decision:r.decision,note:reviewNote.value});if(job.value?.id===r.id)await load(r.id);reviewVisible.value=false;pendingReview.value=null}catch(e){error(e)}finally{reviewBusy.value=false}}
const pictureVisible=ref(false),picture=ref(null),pictureTitle=ref(''),previewUrl=ref(''),pictureLoading=ref(false)
let pictureSequence=0
// 阈值随任务保存，服务端重算完成后才更新页面，避免与导出结果不一致。
const confidenceThreshold = ref(0.6)
const thresholdSaving = ref(false)
async function changeConfidenceThreshold(value) {
  if (!job.value || active.value || thresholdSaving.value) return
  const id = job.value.id
  const seq = ++requestSequence
  thresholdSaving.value = true
  try {
    await api.updateConfidenceThreshold(id, value)
    if (!disposed && job.value?.id === id && requestSequence === seq) {
      await load(id)
    }
  } catch (e) {
    error(e)
  } finally { thresholdSaving.value = false }
}

const hasLowConfidence=pic=>(pic.ocr?.lines||[]).some(line=>line.score<confidenceThreshold.value)
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
  return pic.ocr.lines.map(line=>({...line,matched:matchedLine(pic,line),lowConfidence:line.score<confidenceThreshold.value}))
    .filter(line=>line.matched||line.lowConfidence).map(line=>{
      const w=pic.ocr.width,h=pic.ocr.height,pad=w*.012,box=line.box||[]
      if(!box.length)return {...line,points:'',left:0,top:0}
      const l=Math.max(0,Math.min(...box.map(p=>p[0]))-pad),t=Math.max(0,Math.min(...box.map(p=>p[1]))-pad)
      const r=Math.min(w,Math.max(...box.map(p=>p[0]))+pad),b=Math.min(h,Math.max(...box.map(p=>p[1]))+pad)
      return {...line,points:[[l,t],[r,t],[r,b],[l,b]].map(p=>p.join(',')).join(' '),left:Math.min(92,l/w*100),top:t/h*100}
    })
})
const previewFailed=ref(false)
function clearPicture(){previewFailed.value=false;pictureSequence++;if(previewUrl.value)URL.revokeObjectURL(previewUrl.value);previewUrl.value='';picture.value=null}
// 识别失败的图片可能没有本地预览，使用已有原图链接供人工核查，不重新执行 OCR。
async function openPicture(p,index){
  clearPicture()
  const seq=pictureSequence
  const selected=p.pictures[index]
  picture.value=selected
  pictureTitle.value=`${selected.kind==='MAIN'?'主图':'详情图'} ${selected.index} · ${p.title || p.itemId}`
  pictureVisible.value=true
  pictureLoading.value=true
  try{
    if(selected.error && !selected.previewKey){
      const url=new URL(selected.url)
      if(url.protocol!=='https:')throw new Error('原图链接不可用')
      previewUrl.value=url.href
    }else{
      const blob=await api.getPicture(job.value.id,p.itemId,index)
      if(seq===pictureSequence&&pictureVisible.value&&!disposed)previewUrl.value=URL.createObjectURL(blob)
    }
  }catch(e){
    if(seq===pictureSequence){previewFailed.value=true;error(e)}
  }finally{
    if(seq===pictureSequence)pictureLoading.value=false
  }
}

onMounted(()=>{loadUsage();usageTimer=setInterval(loadUsage,30000);loadLibraries();try{const saved=JSON.parse(localStorage.getItem('product-scan-rules')||'{}');form.titleWords=saved.titleWords||'';form.imageWords=saved.imageWords||'';form.detectPhones=!!saved.detectPhones;form.detectQrCodes=!!saved.detectQrCodes}catch{}refresh()})
onBeforeUnmount(()=>{disposed=true;requestSequence++;clearTimeout(timer);clearInterval(usageTimer);clearPicture()})
</script>

<style scoped>
.library-picker .muted,.library-picker .error{flex-basis:100%;margin:8px 0 0}

.review-action{display:inline-block;margin-right:12px}.results-placeholder{min-height:180px}.product-title{max-width:100%;justify-content:flex-start;text-align:left;overflow-wrap:anywhere}.filter-word-hint{color:#d93025;font-weight:600;font-size:13px;line-height:1.7;margin:8px 0 0}.csv-import{width:100%;padding:16px;border:1px dashed var(--el-border-color);border-radius:6px}.csv-name{overflow-wrap:anywhere}.results{margin-top:20px}.subtitle{margin-left:16px;color:var(--el-text-color-secondary);font-size:13px}.toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:12px 0}.muted{color:var(--el-text-color-secondary);font-size:13px;line-height:1.7}.summary{display:flex;gap:14px;margin:18px 0}.summary>div{flex:1;padding:16px;background:var(--el-fill-color-light);border-radius:6px}.summary strong{display:block;font-size:26px;margin-top:8px}.summary-matched strong,.summary-provider-error strong{color:var(--el-color-danger)}.summary-review strong{color:var(--el-color-warning)}.summary-incomplete strong{color:var(--el-color-primary)}.summary-passed strong{color:var(--el-color-success)}.evidence{display:flex;flex-wrap:wrap;gap:7px;margin:10px 0}.evidence .el-button{margin-left:0}.retry-action{margin-top:12px}.retry-button{margin-left:0;font-weight:700;box-shadow:0 2px 6px rgb(245 108 108 / 35%)}.result-table{margin-top:18px}.scan-warnings{margin-top:10px;color:var(--el-color-warning-dark-2)}.error{color:var(--el-color-danger)}.picture-grid{display:grid;grid-template-columns:1.2fr 1fr;gap:24px;min-height:180px}.preview-frame{position:relative;align-self:start}.preview-frame img{display:block;width:100%}.preview-frame svg{position:absolute;inset:0;width:100%;height:100%;pointer-events:none}.hit-marker{position:absolute;transform:translateY(calc(-100% - 4px));background:#e11d2e;color:white;border:2px solid white;border-radius:6px;padding:3px 7px;font-weight:bold;pointer-events:none}.hit-line{display:flex;align-items:center;gap:12px;background:#fff5f3;color:#303133;padding:14px;margin:10px 0;border-radius:8px}.hit-line b{background:#e11d2e;color:white;padding:4px 8px;border-radius:5px}@media(max-width:800px){.picture-grid{grid-template-columns:1fr}.summary{flex-wrap:wrap}.summary>div{min-width:40%}.subtitle{display:block;margin:8px 0}}

/* 表单按操作顺序分区，窄屏时纵向排列。 */
.scan-page{background:var(--el-fill-color-lighter);min-height:100%}
.scan-page :deep(.el-card){border-radius:12px;border-color:var(--el-border-color-lighter)}
.scan-page :deep(.el-card__header){padding:20px 24px}
.setup-card :deep(.el-card__body){padding:0}
.card-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}
.header-tags{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.card-heading h2{font-size:18px;line-height:1.4;margin:0;color:var(--el-text-color-primary)}
.card-heading p{font-size:13px;margin:6px 0 0;color:var(--el-text-color-secondary)}
.setup-grid{display:grid;grid-template-columns:minmax(260px,30%) minmax(0,1fr)}
.import-section,.rules-section{padding:24px;min-width:0}
.import-section{border-right:1px solid var(--el-border-color-lighter);background:var(--el-fill-color-extra-light)}
.section-heading{display:flex;align-items:center;gap:10px;margin-bottom:24px}
.section-heading h3{font-size:15px;margin:0;font-weight:600}
.step-number{display:grid;place-items:center;width:26px;height:26px;border-radius:8px;background:var(--el-color-primary-light-9);color:var(--el-color-primary);font-weight:700;font-size:13px}
.setup-card :deep(.el-form-item__label){font-weight:500;color:var(--el-text-color-regular)}
.csv-import{display:flex;flex-direction:column;align-items:center;gap:14px;text-align:center;padding:26px 16px;background:var(--el-bg-color);border-color:var(--el-color-primary-light-7);border-radius:10px}
.csv-import .el-button+.el-button{margin-left:0}
.upload-icon{font-size:32px;color:var(--el-color-primary);padding:12px;box-sizing:content-box;background:var(--el-color-primary-light-9);border-radius:14px}
.upload-title{font-size:14px;font-weight:500}
.csv-import p{margin:0}
.upload-limit{font-size:12px;color:var(--el-text-color-secondary);line-height:1.7}
.import-note{margin:0}
.library-picker{margin-bottom:22px}
.library-controls{display:flex;align-items:center;gap:10px;width:100%;flex-wrap:wrap}
.library-controls .el-button+.el-button{margin-left:0}
.library-select{flex:1;min-width:180px}
.library-picker .muted{font-size:12px;margin-top:10px}
.rules-section :deep(.el-textarea__inner){padding:12px;line-height:1.8;border-radius:8px}
.rules-section .el-checkbox{margin-top:-8px}
.filter-word-hint{display:flex;gap:12px;border:1px solid var(--el-color-danger-light-8);background:var(--el-color-danger-light-9);border-radius:8px;padding:12px 14px;margin-top:20px;font-size:12px;font-weight:500;color:var(--el-color-danger-dark-2)}
.filter-word-hint strong{white-space:nowrap}
.scan-actions{border-top:1px solid var(--el-border-color-lighter);padding:16px 24px;display:flex;align-items:center;justify-content:space-between;gap:16px}
.scan-actions>.el-button{min-width:150px;border-radius:8px}
.action-status{display:flex;align-items:center;gap:8px;font-size:13px;color:var(--el-text-color-secondary)}
.action-status strong{color:var(--el-color-primary)}
.results :deep(.el-card__body){padding:20px 24px}
.summary{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}
.summary>div{padding:18px;border:1px solid var(--el-border-color-lighter);background:var(--el-fill-color-extra-light);border-radius:10px;color:var(--el-text-color-regular);font-size:13px}
.summary strong{font-variant-numeric:tabular-nums;font-size:28px;color:var(--el-text-color-primary)}
.summary-matched strong,.summary-provider-error strong{color:var(--el-color-danger)}
.summary-review strong{color:var(--el-color-warning-dark-2)}
.summary-incomplete strong{color:var(--el-color-primary)}
.summary-passed strong{color:var(--el-color-success)}
.result-table :deep(th.el-table__cell){background:var(--el-fill-color-light)}
.result-table :deep(.el-table__cell){padding:14px 0}
.toolbar>.el-button+.el-button{margin-left:0}
@media(max-width:1100px){.setup-grid{grid-template-columns:minmax(250px,32%) minmax(0,1fr)}.import-section,.rules-section{padding:20px}}
@media(max-width:900px){.setup-grid{grid-template-columns:1fr}.import-section{border-right:0;border-bottom:1px solid var(--el-border-color-lighter)}.summary{grid-template-columns:repeat(2,minmax(0,1fr))}.card-heading>.el-tag{display:none}}
@media(max-width:600px){.scan-page{padding:12px}.scan-actions{align-items:stretch;flex-direction:column}.filter-word-hint{flex-direction:column;gap:4px}.card-heading{align-items:flex-start}.results :deep(.el-card__body){padding:16px}.toolbar .el-select{max-width:100%}}
.result-pagination{padding:20px 0 0;background:transparent}
.result-pagination :deep(.el-pagination){flex-wrap:wrap;gap:8px;justify-content:flex-end}
.task-progress{margin:20px 0;padding:18px 20px;border:1px solid var(--el-border-color-lighter);border-radius:10px;background:var(--el-fill-color-extra-light)}
.progress-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:14px;font-size:14px}
.progress-heading>span,.progress-details{font-size:13px;color:var(--el-text-color-secondary)}
.progress-details{display:flex;flex-wrap:wrap;gap:8px 20px;margin-top:10px;line-height:1.7;overflow-wrap:anywhere}
.confidence-controls{display:flex;align-items:center;flex-wrap:wrap;gap:10px 12px;margin:16px 0;padding:12px 14px;background:var(--el-fill-color-extra-light);border-radius:8px}
.confidence-controls label{font-size:13px;font-weight:500}
.confidence-controls>span{font-size:12px;line-height:1.7;color:var(--el-text-color-secondary)}
.evidence .picture-failed{
  --el-button-text-color:#a66b16;
  --el-button-bg-color:#fff4de;
  --el-button-border-color:#e7bf78;
  --el-button-hover-text-color:#fff;
  --el-button-hover-bg-color:#b87b25;
  --el-button-hover-border-color:#b87b25;
  --el-button-active-bg-color:#9d671b;
  --el-button-active-border-color:#9d671b;
}
</style>
