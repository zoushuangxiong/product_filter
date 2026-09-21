<template>
  <div class="app-container checked-filter-page">
    <el-card shadow="never">
      <template #header><div class="card-heading"><div><h2>已检测商品过滤</h2><p>导入商品 CSV，过滤当前账号历史上已成功检测过的商品</p></div><el-tag type="info" effect="plain">保留原始 CSV 格式</el-tag></div></template>
      <el-form label-position="top">
        <div class="filter-grid">
          <el-form-item label="商品平台（必选）"><el-select v-model="platform" placeholder="请选择淘宝/天猫或1688" :disabled="busy" style="width:100%"><el-option label="淘宝 / 天猫" value="taobao"/><el-option label="1688" value="1688"/></el-select></el-form-item>
          <el-form-item label="商品 CSV 文件"><div class="file-box"><el-icon class="upload-icon"><UploadFilled /></el-icon><strong>{{ fileName || '选择 CSV 文件' }}</strong><input ref="fileInput" type="file" accept=".csv" hidden @change="importFile"><el-button type="primary" plain :disabled="busy || !platform" @click="fileInput.click()">{{ fileName ? '重新选择 CSV' : '选择 CSV' }}</el-button><span class="muted">最多 10,000 条，不超过 20 MB</span></div></el-form-item>
        </div>
        <el-alert title="只过滤历史上第三方成功返回过商品信息的商品；获取失败、超时的商品会保留。重复记录会按原 CSV 顺序保留。" type="info" :closable="false" show-icon/>
        <div class="actions"><el-button type="primary" size="large" :loading="busy" :disabled="!sourceText" @click="filterChecked">开始过滤</el-button><el-button :disabled="!stats?.remaining || busy" @click="download">下载待检测记录 CSV</el-button></div>
      </el-form>
    </el-card>
    <el-card v-if="stats" shadow="never" class="stats-card"><div class="stats"><div>原始记录<strong>{{ stats.total }}</strong></div><div>已检测记录<strong class="removed">{{ stats.removed }}</strong></div><div>待检测记录<strong class="remaining">{{ stats.remaining }}</strong><el-button class="download-pending" type="primary" plain :disabled="!stats.remaining || busy" @click="download">下载待检测记录 CSV</el-button></div></div><p class="muted">{{ stats.remaining ? '下载仅包含待检测记录，保留原表头、原列和原始顺序，可直接导入商品检测。' : '所有商品均已检测，没有待检测记录可下载。' }}</p></el-card>
  </div>
</template>

<script setup name="ProductCheckedFilter">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { saveAs } from 'file-saver'
import * as api from '@/api/product/scan'

const platform = ref(''), fileName = ref(''), sourceText = ref(''), filteredText = ref(''), rows = ref([]), stats = ref(null), busy = ref(false), fileInput = ref(null)
// 平台变化后必须重新过滤，避免下载上一平台的旧结果。
watch(platform, () => { filteredText.value = ''; stats.value = null })
function error(e) { ElMessage.error(e?.message || '操作失败，请稍后重试') }
function decode(buffer) { try { return new TextDecoder('utf-8', { fatal: true }).decode(buffer) } catch { return new TextDecoder('gb18030').decode(buffer) } }
function csvRows(text) {
  const result = []; let row = [], cell = '', quoted = false
  for (let i = 0; i < text.length; i++) { const c = text[i]; if (c === '"') { if (quoted && text[i + 1] === '"') { cell += '"'; i++ } else quoted = !quoted } else if (c === ',' && !quoted) { row.push(cell); cell = '' } else if ((c === '\n' || c === '\r') && !quoted) { if (c === '\r' && text[i + 1] === '\n') i++; row.push(cell); result.push(row); row = []; cell = '' } else cell += c }
  if (cell || row.length) { row.push(cell); result.push(row) }; if (quoted) throw Error('CSV 引号未闭合'); return result
}
function csv(value) { const text = String(value ?? ''); return `"${text.replaceAll('"', '""')}"` }
async function importFile(event) {
  const file = event.target.files?.[0]; event.target.value = ''
  try { if (!file) return; if (!platform.value) throw Error('请先选择商品平台'); if (!file.name.toLowerCase().endsWith('.csv')) throw Error('只支持 CSV 文件'); if (file.size > 20 * 1024 * 1024) throw Error('CSV 文件不能超过 20 MB'); const text = decode(await file.arrayBuffer()).replace(/^\uFEFF/, ''); const parsed = csvRows(text); const records = parsed.slice(1).filter(r => r.some(c => c.trim())); if (!records.length || records.length > 10000) throw Error('每次请导入 1 至 10,000 条商品记录'); sourceText.value = text; rows.value = parsed; filteredText.value = ''; stats.value = null; fileName.value = file.name; ElMessage.success(`已导入 ${records.length} 条记录`) } catch (e) { error(e) }
}
function itemColumn() { const header = rows.value[0] || []; const index = header.findIndex(v => ['商品ID', '商品id', 'itemId', 'item_id', '商品链接'].includes(v.trim())); return index >= 0 ? index : 0 }
function itemId(value) { const text = String(value || '').trim(); if (/^[1-9][0-9]{0,19}$/.test(text)) return text; const match = platform.value === '1688' ? text.match(/detail\.1688\.com\/offer\/([1-9][0-9]{0,19})\.html/i) : text.match(/[?&]id=([1-9][0-9]{0,19})/i); return match?.[1] || text }
async function filterChecked() {
  if (busy.value || !sourceText.value) return
  busy.value = true
  filteredText.value = ''; stats.value = null
  try { const checked = new Set((await api.getCheckedIds(platform.value)).data || []); const column = itemColumn(); const kept = [rows.value[0]]; let total = 0, removed = 0; for (const row of rows.value.slice(1)) { if (!row.some(c => c.trim())) continue; total++; if (checked.has(itemId(row[column]))) removed++; else kept.push(row) }; filteredText.value = kept.map(row => row.map(csv).join(',')).join('\r\n') + '\r\n'; stats.value = { total, removed, remaining: total - removed }; ElMessage.success(`过滤完成，已移除 ${removed} 条已检测记录`) } catch (e) { error(e) } finally { busy.value = false }
}
function download() { if (busy.value || !stats.value?.remaining || !filteredText.value) return; saveAs(new Blob(['\uFEFF' + filteredText.value], { type: 'text/csv;charset=utf-8' }), `待检测-${fileName.value || '商品.csv'}`) }
</script>

<style scoped>
.download-pending{margin-top:12px}
.checked-filter-page{background:var(--el-fill-color-lighter);min-height:100%}.checked-filter-page :deep(.el-card){border-radius:12px;margin-bottom:20px}.checked-filter-page :deep(.el-card__header){padding:20px 24px}.card-heading{display:flex;justify-content:space-between;align-items:center;gap:16px}.card-heading h2{font-size:18px;margin:0}.card-heading p{color:var(--el-text-color-secondary);font-size:13px;margin:6px 0 0}.filter-grid{display:grid;grid-template-columns:280px minmax(0,1fr);gap:24px}.file-box{display:flex;align-items:center;flex-wrap:wrap;gap:12px;padding:20px;border:1px dashed var(--el-color-primary-light-5);border-radius:10px}.upload-icon{font-size:24px;color:var(--el-color-primary)}.muted{color:var(--el-text-color-secondary);font-size:13px;line-height:1.7}.actions{display:flex;gap:12px;margin-top:24px}.stats{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}.stats>div{padding:18px;border:1px solid var(--el-border-color-lighter);border-radius:10px}.stats strong{display:block;font-size:28px;margin-top:8px}.stats .removed{color:var(--el-color-warning)}.stats .remaining{color:var(--el-color-success)}@media(max-width:700px){.filter-grid{grid-template-columns:1fr}.stats{grid-template-columns:1fr}.card-heading{align-items:flex-start}.card-heading>.el-tag{display:none}}
</style>
