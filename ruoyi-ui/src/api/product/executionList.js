import request from '@/utils/request'

// 查询执行清单列表
export function listExecutionList(query) {
  return request({ url: '/product/execution-list/list', method: 'get', params: query })
}

// 查询执行清单详细信息
export function getExecutionList(id) {
  return request({ url: '/product/execution-list/' + id, method: 'get' })
}

// 新增执行清单
export function addExecutionList(data) {
  // 表格可能较大，不写入防重复提交的会话缓存；页面提交锁和数据库唯一索引负责防重。
  return request({ url: '/product/execution-list', method: 'post', data, timeout: 60000, headers: { repeatSubmit: false, 'Content-Type': 'multipart/form-data' } })
}

// 修改执行清单
export function updateExecutionList(data) {
  return request({ url: '/product/execution-list', method: 'put', data, timeout: 60000, headers: { repeatSubmit: false } })
}

// 删除执行清单
export function delExecutionList(id) {
  return request({ url: '/product/execution-list/' + id, method: 'delete' })
}

// 为当前账号生成已检测过滤快照
export function filterExecutionList(id) {
  return request({ url: `/product/execution-list/${id}/filter`, method: 'post', timeout: 60000 })
}

// 检测页分页搜索清单，仅返回统计和版本，不读取文件内容
export function executionListOptions(query) {
  return request({ url: '/product/execution-list/options', params: query })
}

export function getExecutionListOption(id) {
  return request({ url: `/product/execution-list/options/${id}` })
}
