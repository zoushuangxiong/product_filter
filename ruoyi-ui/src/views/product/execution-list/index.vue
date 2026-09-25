<template>
  <div class="app-container">
    <el-form ref="queryRef" :model="queryParams" :inline="true" v-show="showSearch" @submit.prevent="handleQuery">
      <el-form-item label="清单名称" prop="fileName">
        <el-input v-model="queryParams.fileName" placeholder="请输入清单名称" clearable @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item label="所属平台" prop="platform">
        <el-select v-model="queryParams.platform" placeholder="全部平台" clearable style="width: 150px">
          <el-option label="淘宝 / 天猫" value="taobao" />
          <el-option label="1688" value="1688" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button type="primary" plain icon="Plus" @click="handleAdd" v-hasPermi="['product:executionList:add']">新增</el-button></el-col>
      <el-col :span="1.5"><el-button type="success" plain icon="Edit" :disabled="ids.length !== 1 || loading" @click="handleUpdate()" v-hasPermi="['product:executionList:edit']">修改</el-button></el-col>
      <el-col :span="1.5"><el-button type="danger" plain icon="Delete" :disabled="multiple || loading" @click="handleDelete()" v-hasPermi="['product:executionList:remove']">删除</el-button></el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="getList" />
    </el-row>
    <el-alert v-if="loadError" title="执行清单加载失败，请点击刷新重试" type="error" :closable="false" show-icon />
    <el-table v-loading="loading" :data="executionListList" row-key="id" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="编号" prop="id" width="80" align="center" />
      <el-table-column label="清单名称" prop="fileName" min-width="220" align="center" show-overflow-tooltip />
      <el-table-column label="所属平台" prop="platform" width="120" align="center">
        <template #default="scope"><el-tag :type="scope.row.platform === '1688' ? 'warning' : 'primary'">{{ scope.row.platform === '1688' ? '1688' : '淘宝 / 天猫' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="原始清单" align="center">
        <el-table-column label="记录数" prop="recordCount" width="100" align="center" />
        <el-table-column label="商品数" prop="productCount" width="100" align="center" />
      </el-table-column>
      <el-table-column label="过滤已检测后" align="center">
        <el-table-column label="记录数" width="100" align="center"><template #default="{ row }">{{ row.filteredRecordCount ?? '未过滤' }}</template></el-table-column>
        <el-table-column label="商品数" width="100" align="center"><template #default="{ row }">{{ row.filteredProductCount ?? '未过滤' }}</template></el-table-column>
      </el-table-column>
      <el-table-column label="备注" prop="remark" min-width="130" align="center" show-overflow-tooltip />
      <el-table-column label="创建时间" prop="createTime" width="165" align="center" />
      <el-table-column label="操作" align="center" width="350" fixed="right">
        <template #default="scope">
          <el-button link type="primary" icon="View" :disabled="loading" @click="handleResults(scope.row)" v-hasPermi="['product:scan:use']">检测结果</el-button>
          <el-button link type="primary" :loading="filteringId === scope.row.id" :disabled="loading || !!filteringId" @click="handleFilter(scope.row)" v-hasPermi="['product:executionList:filter']">过滤已检测</el-button>
          <el-button link type="primary" icon="Edit" :disabled="loading" @click="handleUpdate(scope.row)" v-hasPermi="['product:executionList:edit']">修改</el-button>
          <el-button link type="primary" icon="Delete" :disabled="loading" @click="handleDelete(scope.row)" v-hasPermi="['product:executionList:remove']">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />

    <el-dialog :title="title" v-model="open" width="640px" style="max-width: 95vw" :close-on-click-modal="false" :show-close="!saving && !reading" :close-on-press-escape="!saving && !reading">
      <el-form ref="executionListRef" :model="form" :rules="rules" label-width="100px" :disabled="saving || reading">
        <el-form-item label="所属平台" prop="platform">
          <el-select v-model="form.platform" :disabled="!!form.id" placeholder="请选择商品所属平台" style="width: 100%">
            <el-option label="淘宝 / 天猫" value="taobao" /><el-option label="1688" value="1688" />
          </el-select>
        </el-form-item>
        <el-form-item label="清单文件" prop="fileName">
          <div class="upload-area">
            <input v-if="!form.id" ref="fileInput" type="file" accept=".csv,.xlsx" hidden @change="handleFileChange" />
            <el-button v-if="!form.id" type="primary" plain icon="Upload" :loading="reading" @click="fileInput.click()">{{ form.fileName ? '替换文件' : '选择文件' }}</el-button>
            <el-button v-if="!form.id" link type="primary" @click="downloadTemplate">下载 CSV 模板</el-button>
            <div v-if="form.fileName" class="file-name">{{ form.fileName }}</div>
            <div v-if="form.recordCount != null" class="file-count">文件记录数：{{ form.recordCount }} 条<span v-if="form.id">，商品数量：{{ form.productCount }} 个</span></div>
            <p v-if="!form.id" class="upload-hint">支持 CSV、Excel（.xlsx），最多 10,000 条、不超过 20 MB。必须有表头，包含“商品链接”或“商品ID”列。Excel 读取第一张工作表。</p>
            <p v-else class="upload-hint">仅允许修改备注。</p>
          </div>
        </el-form-item>
        <el-form-item label="备注" prop="remark"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="请输入备注" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" :loading="saving" :disabled="reading" @click="submitForm">确 定</el-button>
        <el-button :disabled="saving || reading" @click="cancel">取 消</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="ProductExecutionList">
import { getCurrentInstance, reactive, ref, toRefs, nextTick } from 'vue'
import { listExecutionList, getExecutionList, addExecutionList, updateExecutionList, delExecutionList, filterExecutionList } from '@/api/product/executionList'
import { saveAs } from 'file-saver'
import usePermissionStore from '@/store/modules/permission'

const { proxy } = getCurrentInstance()
const executionListList = ref([])
const open = ref(false)
const loading = ref(false)
const saving = ref(false)
const reading = ref(false)
const loadError = ref(false)
const ids = ref([])
const multiple = ref(true)
const showSearch = ref(true)
const total = ref(0)
const title = ref('')
const fileInput = ref()
const selectedFile = ref(null)
const filteringId = ref(null)
const data = reactive({
  form: {},
  queryParams: { pageNum: 1, pageSize: 10, fileName: undefined, platform: undefined },
  rules: {
    platform: [{ required: true, message: '请选择商品平台', trigger: 'change' }],
    fileName: [{ required: true, message: '请上传商品文件', trigger: 'change' }]
  }
})
const { queryParams, form, rules } = toRefs(data)
let listVersion = 0

/** 查询执行清单列表，忽略过期请求，避免快速搜索覆盖新结果。 */
async function getList() {
  const version = ++listVersion
  loading.value = true
  loadError.value = false
  try {
    const response = await listExecutionList({ ...queryParams.value })
    if (version !== listVersion) return
    executionListList.value = response.rows
    total.value = response.total
    ids.value = []
    multiple.value = true
  } catch { if (version === listVersion) loadError.value = true }
  finally { if (version === listVersion) loading.value = false }
}

/** 表单重置。 */
function reset() {
  selectedFile.value = null
  form.value = { id: undefined, platform: undefined, fileName: '', remark: '' }
  proxy.resetForm('executionListRef')
}

/** 取消按钮。 */
function cancel() { open.value = false; reset() }

/** 搜索按钮操作。 */
function handleQuery() { queryParams.value.pageNum = 1; getList() }

/** 重置按钮操作。 */
function resetQuery() { proxy.resetForm('queryRef'); handleQuery() }

/** 多选框选中数据。 */
function handleSelectionChange(selection) {
  ids.value = selection.map(item => item.id)
  multiple.value = !selection.length
}

/** 新增按钮操作。 */
function handleAdd() {
  reset()
  open.value = true
  title.value = '添加执行清单'
}

/** 修改清单，只开放备注，其他字段用于只读展示。 */
async function handleUpdate(row) {
  loading.value = true
  try {
    const response = await getExecutionList(row?.id || ids.value[0])
    if (!response.data) return proxy.$modal.msgError('执行清单不存在或已删除')
    reset()
    form.value = response.data
    open.value = true
    title.value = '修改执行清单备注'
    await nextTick()
    proxy.$refs.executionListRef?.clearValidate()
  } catch {} finally { loading.value = false }
}

/** 读取文件，只在解析成功后替换当前选择；最终数量由服务端计算。 */
async function handleFileChange(event) {
  const file = event.target.files[0]
  if (!file) return
  reading.value = true
  try {
    if (!/\.(csv|xlsx)$/i.test(file.name)) throw Error('只支持 CSV 或 Excel（.xlsx）文件')
    if (file.size > 20 * 1024 * 1024) throw Error('文件不能超过20 MB')
    selectedFile.value = file
    form.value.fileName = file.name
    proxy.$refs.executionListRef?.clearValidate('fileName')
  } catch (error) { proxy.$modal.msgError(error.message) }
  finally { reading.value = false; event.target.value = '' }
}

/** 模板入口随文件导入一起移到执行清单。 */
function downloadTemplate() {
  const columns = ['序号', '关键词', '主图', '商品链接', '类目', '商品标题', '店铺链接', '价格', '销量', '评论数', '状态']
  saveAs(new Blob(['\uFEFF' + columns.join(',') + '\r\n'], { type: 'text/csv;charset=utf-8' }), '商品导入模板.csv')
}

/** 新增时提交表格；修改时只提交主键和备注。 */
async function submitForm() {
  if (saving.value || reading.value || !await proxy.$refs.executionListRef.validate().catch(() => false)) return
  saving.value = true
  try {
    if (form.value.id) {
      await updateExecutionList({ id: form.value.id, remark: form.value.remark || '' })
    } else {
      if (!selectedFile.value) return proxy.$modal.msgWarning('请选择文件')
      const payload = new FormData()
      payload.append('file', selectedFile.value)
      payload.append('platform', form.value.platform)
      payload.append('remark', form.value.remark || '')
      await addExecutionList(payload)
    }
    proxy.$modal.msgSuccess(form.value.id ? '修改成功' : '新增成功')
    open.value = false
    await getList()
  } catch {} finally { saving.value = false }
}

/** 按当前账号的历史成功记录过滤；重复过滤始终基于原始文件。 */
async function handleFilter(row) {
  if (filteringId.value) return
  try { await proxy.$modal.confirm('按当前账号、同一平台的历史获取成功记录过滤。原始文件保留，获取失败和超时的商品不会被过滤。是否继续？') }
  catch { return }
  filteringId.value = row.id
  try {
    const { data } = await filterExecutionList(row.id)
    proxy.$modal.msgSuccess(`过滤完成：${data.recordCount} 条记录 / ${data.productCount} 个商品 → ${data.filteredRecordCount} 条记录 / ${data.filteredProductCount} 个商品`)
    await getList()
  } catch {} finally { filteringId.value = null }
}

/** 跳转到所选清单的检测结果，不创建或启动任务。 */
function handleResults(row) {
  const path = usePermissionStore().productScanPath
  if (!path) return proxy.$modal.msgWarning('未找到商品检测菜单，请确认菜单权限')
  proxy.$router.push({ path, query: { executionListId: String(row.id) } })
}

/** 删除按钮操作。 */
async function handleDelete(row) {
  const selected = row?.id ? [row.id] : [...ids.value]
  try { await proxy.$modal.confirm(`是否确认删除${row?.fileName ? '清单“' + row.fileName + '”' : '选中的 ' + selected.length + ' 份执行清单'}？`) }
  catch { return }
  loading.value = true
  try {
    await delExecutionList(selected.join(','))
    proxy.$modal.msgSuccess('删除成功')
    if (executionListList.value.length <= selected.length && queryParams.value.pageNum > 1) queryParams.value.pageNum--
    await getList()
  } catch {} finally { loading.value = false }
}

getList()
</script>

<style scoped>
.upload-hint{font-size:13px;color:var(--el-text-color-secondary);line-height:1.7}
.upload-area{width:100%;padding:16px;border:1px dashed var(--el-border-color);border-radius:6px;background:var(--el-fill-color-extra-light)}
.file-name{margin-top:10px;overflow-wrap:anywhere;color:var(--el-text-color-primary)}
.file-count{margin-top:6px;color:var(--el-color-primary)}
.upload-hint{margin:8px 0 0}
</style>
