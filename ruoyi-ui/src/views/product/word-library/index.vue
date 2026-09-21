<template>
  <div class="app-container">
    <el-form :inline="true" @submit.prevent="search">
      <el-form-item label="词库名称"><el-input v-model="query.name" placeholder="请输入词库名称" clearable maxlength="50" @keyup.enter="search"/></el-form-item>
      <el-form-item><el-button type="primary" icon="Search" @click="search">搜索</el-button><el-button @click="resetSearch">重置</el-button></el-form-item>
    </el-form>
    <el-row class="mb8"><el-button v-hasPermi="['product:wordLibrary:add']" type="primary" plain icon="Plus" :disabled="loading" @click="openAdd">新增词库</el-button></el-row>
    <p class="muted">词库按当前账号保存，可在商品检测页快速选择。修改或删除词库不会改变已创建的检测任务。</p>
    <el-alert v-if="loadError" title="词库加载失败，请点击搜索重试" type="error" :closable="false" show-icon/>
    <el-table v-loading="loading" :data="rows" row-key="id">
      <el-table-column label="编号" prop="id" width="90"/>
      <el-table-column label="词库名称" prop="name" min-width="180" show-overflow-tooltip/>
      <el-table-column label="检测手机号" width="120"><template #default="{ row }">{{ row.detectPhones ? '是' : '否' }}</template></el-table-column>
      <el-table-column label="创建时间" prop="createTime" width="180"/>
      <el-table-column label="修改时间" prop="updateTime" width="180"/>
      <el-table-column label="操作" width="200"><template #default="{ row }">
        <el-button link type="primary" :disabled="loading" @click="openEdit(row, true)">查看</el-button>
        <el-button v-hasPermi="['product:wordLibrary:edit']" link type="primary" :disabled="loading" @click="openEdit(row)">编辑</el-button>
        <el-button v-hasPermi="['product:wordLibrary:remove']" link type="danger" :disabled="loading" @click="remove(row)">删除</el-button>
      </template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="load"/>
    <el-dialog v-model="dialog" :title="readOnly ? '查看词库' : form.id ? '编辑词库' : '新增词库'" width="720px" style="max-width:95vw" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" :disabled="saving || readOnly">
        <el-form-item label="词库名称" prop="name"><el-input v-model="form.name" maxlength="50" show-word-limit placeholder="例如：通用过滤词、服饰过滤词"/></el-form-item>
        <el-form-item label="标题过滤词" prop="titleWords"><el-input v-model="form.titleWords" type="textarea" :rows="6" maxlength="30000" placeholder="每行一个过滤词"/></el-form-item>
        <el-form-item label="图片文字过滤词" prop="imageWords"><el-input v-model="form.imageWords" type="textarea" :rows="6" maxlength="30000" placeholder="每行一个过滤词；不填写时需启用手机号检测"/></el-form-item>
        <el-checkbox v-model="form.detectPhones">同时筛查图片中的 11 位手机号</el-checkbox>
      </el-form>
      <p class="word-hint">按换行分隔：每行一个词，按回车换行；逗号、顿号、空格不会分隔词语。标题和图片各最多 500 个词，每词最多 100 字符，每份最多 30,000 字符。标题过滤词必填；图片过滤词和手机号检测至少选一项。</p>
      <template #footer><el-button :disabled="saving" @click="dialog = false">{{ readOnly ? '关闭' : '取消' }}</el-button><el-button v-if="!readOnly" type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup name="ProductWordLibrary">
import { ref, reactive, nextTick, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/product/wordLibrary'

const query = reactive({ name: '', pageNum: 1, pageSize: 10 })
const rows = ref([]), total = ref(0), loading = ref(false), loadError = ref(false)
const dialog = ref(false), saving = ref(false), readOnly = ref(false), formRef = ref()
const blank = () => ({ id: null, name: '', titleWords: '', imageWords: '', detectPhones: false })
const form = reactive(blank())
// 与服务端保持一致：按换行拆词并去重，标点和词内空格属于词语内容。
function validateWords(required) {
  return (_rule, value, done) => {
    const text = value || ''
    const words = [...new Set(text.split(/\r\n|[\n\r\v\f\u0085\u2028\u2029]/).map(s => s.trim()).filter(Boolean))]
    if (required && !words.length) return done(new Error('请填写标题过滤词'))
    if (text.length > 30000 || words.length > 500 || words.some(s => s.length > 100)) return done(new Error('最多 500 个词，每词最多 100 字符，总计最多 30,000 字符'))
    if (!required && !words.length && !form.detectPhones) return done(new Error('请填写图片文字过滤词，或启用手机号检测'))
    done()
  }
}
const rules = {
  name: [{ required: true, whitespace: true, message: '请填写词库名称', trigger: 'blur' }],
  titleWords: [{ validator: validateWords(true), trigger: 'blur' }],
  imageWords: [{ validator: validateWords(false), trigger: 'blur' }]
}
let loadVersion = 0
async function load() {
  const version = ++loadVersion
  loading.value = true
  loadError.value = false
  try {
    const res = await api.listLibraries({ ...query })
    if (version === loadVersion) { rows.value = res.rows; total.value = res.total }
  } catch { if (version === loadVersion) loadError.value = true }
  finally { if (version === loadVersion) loading.value = false }
}
function search() { query.pageNum = 1; load() }
function resetSearch() { query.name = ''; search() }
async function openAdd() {
  readOnly.value = false
  Object.assign(form, blank())
  dialog.value = true
  await nextTick()
  formRef.value?.clearValidate()
}
async function openEdit(row, view = false) {
  loading.value = true
  try {
    const { data } = await api.getLibrary(row.id)
    Object.assign(form, blank(), data)
    readOnly.value = view
    dialog.value = true
    await nextTick()
    formRef.value?.clearValidate()
  } catch {} finally { loading.value = false }
}
async function save() {
  if (saving.value || !await formRef.value.validate().catch(() => false)) return
  saving.value = true
  try {
    const data = { id: form.id, name: form.name, titleWords: form.titleWords, imageWords: form.imageWords, detectPhones: form.detectPhones }
    await (form.id ? api.updateLibrary(data) : api.addLibrary(data))
    ElMessage.success('词库已保存')
    dialog.value = false
    await load()
  } catch {} finally { saving.value = false }
}
async function remove(row) {
  try { await ElMessageBox.confirm(`确认删除词库“${row.name}”？已创建的检测任务不受影响。`, '删除词库', { type: 'warning' }) } catch { return }
  loading.value = true
  try {
    await api.deleteLibrary(row.id)
    ElMessage.success('词库已删除')
    if (rows.value.length === 1 && query.pageNum > 1) query.pageNum--
    await load()
  } catch {} finally { loading.value = false }
}
onMounted(load)
</script>

<style scoped>
.muted{color:var(--el-text-color-secondary);font-size:13px;line-height:1.7}
.word-hint{color:#d93025;font-weight:600;font-size:13px;line-height:1.7}
</style>
