import request from '@/utils/request'
const base = '/product/scan/tasks'
export const getUsage = () => request({ url: `${base}/usage` })
export const getCheckedIds = platform => request({ url: `${base}/checked-ids`, params: { platform } })
export const listTasks = () => request({ url: base })
export const getTask = id => request({ url: `${base}/${id}` })
export const createTask = data => request({ url: base, method: 'post', data })
export const cancelTask = id => request({ url: `${base}/${id}/cancel`, method: 'post' })
export const updateConfidenceThreshold = (id, value) => request({ url: `${base}/${id}/confidence-threshold`, method: 'post', params: { value } })
export const reviewProduct = (id, itemId, data) => request({ url: `${base}/${id}/products/${itemId}/review`, method: 'post', data })
async function binary(url, params) {
  const blob = await request({ url, params, responseType: 'blob' })
  if (blob.type.includes('json')) {
    const data = JSON.parse(await blob.text())
    throw new Error(data.msg || '请求失败')
  }
  return blob
}
export const getPicture = (id, itemId, index) => binary(`${base}/${id}/products/${itemId}/images/${index}`)
export const exportTask = (id, eligibleOnly) => binary(`${base}/${id}/export`, { eligibleOnly })
