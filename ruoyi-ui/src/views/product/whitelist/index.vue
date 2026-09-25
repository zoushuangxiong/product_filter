<template>
  <div class="app-container">
    <el-form ref="queryRef" :model="queryParams" :inline="true" v-show="showSearch" @submit.prevent="handleQuery">
      <el-form-item label="过滤词" prop="filterWord"><el-input v-model="queryParams.filterWord" placeholder="请输入过滤词" clearable @keyup.enter="handleQuery" /></el-form-item>
      <el-form-item label="匹配内容" prop="matchContent"><el-input v-model="queryParams.matchContent" placeholder="请输入白名单匹配内容" clearable @keyup.enter="handleQuery" /></el-form-item>
      <el-form-item><el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button><el-button icon="Refresh" @click="resetQuery">重置</el-button></el-form-item>
    </el-form>
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button type="primary" plain icon="Plus" @click="handleAdd" v-hasPermi="['product:whitelist:add']">新增词库</el-button></el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList" />
    </el-row>
    <el-alert v-if="loadError" title="白名单加载失败，请刷新重试" type="error" :closable="false" show-icon />
    <el-table v-loading="loading" :data="whitelistList" row-key="id">
      <el-table-column label="编号" prop="id" width="90" align="center" />
      <el-table-column label="过滤词" prop="filterWord" min-width="130" align="center" show-overflow-tooltip />
      <el-table-column label="白名单匹配方式" width="150" align="center"><template #default="{ row }">{{ typeLabel(row.matchType) }}</template></el-table-column>
      <el-table-column label="白名单匹配数量" prop="matchCount" width="150" align="center" />
      <el-table-column label="白名单匹配内容" min-width="260" align="center"><template #default="{ row }"><span class="content-preview" :title="preview(row.matchContent)">{{ preview(row.matchContent) || '—' }}</span></template></el-table-column>
      <el-table-column label="是否启用" width="150" align="center">
        <template #default="{ row }"><el-tag :type="row.status === '0' ? 'success' : 'info'">{{ row.status === '0' ? '启用' : '禁用' }}</el-tag><el-button class="status-action" link type="primary" :disabled="!!statusId" :loading="statusId === row.id" @click="handleStatus(row)" v-hasPermi="['product:whitelist:edit']">{{ row.status === '0' ? '禁用' : '启用' }}</el-button></template>
      </el-table-column>
      <el-table-column label="操作" width="230" align="center" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" icon="Plus" @click="handleEdit(row, 'append')" v-hasPermi="['product:whitelist:add']">添加</el-button>
          <el-button link type="primary" icon="Edit" @click="handleEdit(row, 'edit')" v-hasPermi="['product:whitelist:edit']">修改</el-button>
          <el-button link type="danger" icon="Delete" @click="handleDelete(row)" v-hasPermi="['product:whitelist:remove']">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />
    <el-dialog v-model="open" :title="title" width="650px" style="max-width:95vw" :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving">
      <el-form ref="whitelistRef" :model="form" :rules="rules" label-width="145px" :disabled="saving">
        <el-form-item label="过滤词" prop="filterWord"><el-input v-model="form.filterWord" :disabled="mode !== 'add'" maxlength="100" placeholder="例如：ML、RL（每条记录一个过滤词）" /></el-form-item>
        <el-form-item label="白名单匹配方式" prop="matchType"><el-radio-group v-model="form.matchType" :disabled="mode !== 'add'"><el-radio value="UNIT">计量单位</el-radio><el-radio value="WORD">词汇</el-radio></el-radio-group></el-form-item>
        <el-form-item label="白名单匹配内容" prop="matchContent"><el-input v-model="form.matchContent" type="textarea" :rows="9" :placeholder="form.matchType === 'UNIT' ? '每行一项，例如：\n##ML\n###ML' : '每行一项，例如：\nGirl\nWorld\npearl'" /></el-form-item>
      </el-form>
      <p class="word-hint">按换行分隔，每行一项；逗号、顿号和空格不会分隔内容。重复项自动合并，不区分大小写。每项最多200个字符，每条最多10000项。</p>
      <p v-if="form.matchType === 'UNIT'" class="muted">计量单位中，每个 # 表示一位数字，例如 ##ML 表示两位数字加 ML，###ML 表示三位数字加 ML。</p>
      <p v-if="form.matchType === 'WORD'" class="muted">中文按连续文字整体匹配。例如只配置“完美”，不会放行“不完美”或“追求完美”；需要放行的完整内容请单独填写。</p>
      <template #footer><el-button type="primary" :loading="saving" @click="submitForm">确 定</el-button><el-button :disabled="saving" @click="open = false">取 消</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup name="ProductWhitelist">
import { getCurrentInstance, ref, reactive, toRefs, nextTick } from 'vue'
import { listWhitelist, getWhitelist, addWhitelist, appendWhitelist, updateWhitelist, changeWhitelistStatus, delWhitelist } from '@/api/product/whitelist'

const { proxy } = getCurrentInstance()
const whitelistList = ref([]), total = ref(0), loading = ref(false), loadError = ref(false)
const showSearch = ref(true), open = ref(false), saving = ref(false), title = ref(''), mode = ref('add'), statusId = ref(null)
const data = reactive({
  queryParams: { pageNum: 1, pageSize: 10, filterWord: undefined, matchContent: undefined },
  form: {},
  rules: {
    filterWord: [{ required: true, whitespace: true, message: '请输入过滤词', trigger: 'blur' }],
    matchType: [{ required: true, message: '请选择匹配方式', trigger: 'change' }],
    matchContent: [{ validator: validateContent, trigger: 'blur' }]
  }
})
const { queryParams, form, rules } = toRefs(data)
const typeLabel = value => value === 'UNIT' ? '计量单位' : '词汇'
let listVersion = 0, editVersion = 0

/** 列表仅预览部分内容，完整内容在修改弹窗中加载。 */
function preview(content) {
  const text = (content || '').slice(0, 160).replace(/\r?\n/g, '、')
  return text + ((content || '').length > 160 ? '……' : '')
}

/** 换行拆分及数量校验；修改允许清空，追加时必须填写。 */
function validateContent(_rule, value, callback) {
  const lines = (value || '').split(/\r\n|[\n\r\v\f\u0085\u2028\u2029]/).map(line => line.trim()).filter(Boolean)
  if (mode.value === 'append' && !lines.length) return callback(new Error('请填写需要添加的匹配内容'))
  if (lines.some(line => line.length > 200)) return callback(new Error('每项匹配内容最多200个字符'))
  if (new Set(lines.map(line => line.normalize('NFKC').toLowerCase())).size > 10000) return callback(new Error('每条白名单最多10000项匹配内容'))
  callback()
}

/** 查询白名单库列表。 */
async function getList() {
  const version = ++listVersion
  loading.value = true
  loadError.value = false
  try {
    const response = await listWhitelist({ ...queryParams.value })
    if (version !== listVersion) return
    whitelistList.value = response.rows
    total.value = response.total
  } catch { if (version === listVersion) loadError.value = true }
  finally { if (version === listVersion) loading.value = false }
}

/** 搜索按钮操作。 */
function handleQuery() { queryParams.value.pageNum = 1; getList() }

/** 重置查询条件。 */
function resetQuery() { proxy.resetForm('queryRef'); handleQuery() }

/** 新增词库，允许指定过滤词和匹配方式。 */
async function handleAdd() {
  editVersion++
  mode.value = 'add'
  form.value = { filterWord: '', matchType: 'UNIT', matchContent: '' }
  title.value = '新增白名单词库'
  open.value = true
  await nextTick()
  proxy.$refs.whitelistRef?.clearValidate()
}

/** 添加时清空内容，修改时读取完整内容；两种操作均锁定过滤词与匹配方式。 */
async function handleEdit(row, action) {
  const version = ++editVersion
  try {
    const { data } = await getWhitelist(row.id)
    if (version !== editVersion) return
    mode.value = action
    form.value = { ...data, matchContent: action === 'append' ? '' : data.matchContent }
    title.value = action === 'append' ? '添加白名单匹配内容' : '修改白名单匹配内容'
    open.value = true
    await nextTick()
    proxy.$refs.whitelistRef?.clearValidate()
  } catch {}
}

/** 新增重复时必须再次确认；取消不会写入或覆盖已有记录。 */
async function submitForm() {
  if (saving.value || !await proxy.$refs.whitelistRef.validate().catch(() => false)) return
  saving.value = true
  try {
    const payload = { id: form.value.id, filterWord: form.value.filterWord, matchType: form.value.matchType, matchContent: form.value.matchContent || '' }
    if (mode.value === 'add') {
      const { data } = await addWhitelist(payload)
      if (data.duplicate) {
        try { await proxy.$modal.confirm(`该【${payload.filterWord}＋${typeLabel(payload.matchType)}】已存在，配置的白名单匹配内容将直接添加到该项内。是否继续？`) }
        catch { return }
        if (payload.matchContent.trim()) await appendWhitelist({ id: data.id, matchContent: payload.matchContent })
      }
    } else if (mode.value === 'append') {
      await appendWhitelist({ id: payload.id, matchContent: payload.matchContent })
    } else {
      await updateWhitelist({ id: payload.id, matchContent: payload.matchContent })
    }
    proxy.$modal.msgSuccess('保存成功')
    open.value = false
    await getList()
  } catch {} finally { saving.value = false }
}

/** 启用或禁用，只在保存成功后更新列表状态。 */
async function handleStatus(row) {
  if (statusId.value) return
  statusId.value = row.id
  try {
    await changeWhitelistStatus({ id: row.id, status: row.status === '0' ? '1' : '0' })
    proxy.$modal.msgSuccess('状态已更新')
    await getList()
  } catch {} finally { statusId.value = null }
}

/** 有内容时禁止删除，服务端还会锁定记录再次校验。 */
async function handleDelete(row) {
  if (row.matchCount > 0) return proxy.$modal.msgWarning('该过滤词已配置白名单匹配内容，不能删除，请使用禁用功能')
  try { await proxy.$modal.confirm(`是否删除【${row.filterWord}＋${typeLabel(row.matchType)}】？`) }
  catch { return }
  try {
    await delWhitelist(row.id)
    proxy.$modal.msgSuccess('删除成功')
    if (whitelistList.value.length === 1 && queryParams.value.pageNum > 1) queryParams.value.pageNum--
    await getList()
  } catch {}
}

getList()
</script>

<style scoped>
.content-preview{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.status-action{margin-left:8px}
.word-hint{color:#d93025;font-size:13px;font-weight:600;line-height:1.8}
.muted{color:var(--el-text-color-secondary);font-size:13px;line-height:1.8}
</style>
