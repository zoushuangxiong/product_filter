import request from '@/utils/request'

// 查询白名单库列表
export function listWhitelist(query) {
  return request({ url: '/product/whitelist/list', method: 'get', params: query })
}

// 查询白名单详细信息
export function getWhitelist(id) {
  return request({ url: `/product/whitelist/${id}`, method: 'get' })
}

// 新增白名单；重复时服务端返回确认合并所需的已有主键
export function addWhitelist(data) {
  return request({ url: '/product/whitelist', method: 'post', data })
}

// 追加匹配内容
export function appendWhitelist(data) {
  return request({ url: '/product/whitelist/append', method: 'post', data })
}

// 修改匹配内容
export function updateWhitelist(data) {
  return request({ url: '/product/whitelist', method: 'put', data })
}

// 启用或禁用
export function changeWhitelistStatus(data) {
  return request({ url: '/product/whitelist/status', method: 'put', data })
}

// 删除未配置内容的白名单
export function delWhitelist(id) {
  return request({ url: `/product/whitelist/${id}`, method: 'delete' })
}
