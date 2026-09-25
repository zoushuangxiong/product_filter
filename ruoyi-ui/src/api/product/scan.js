import request from '@/utils/request'
const base = '/product/scan/tasks'
export const getUsage = () => request({ url: `${base}/usage` })
export const listTasks = () => request({ url: base })
export const getTask = (id, params) => request({ url: `${base}/${id}`, params, silentError: true })
export const createTask = data => request({ url: base, method: 'post', data, timeout: 60000 })
export const cancelTask = id => request({ url: `${base}/${id}/cancel`, method: 'post' })
export const retryProduct = (id, itemId) => request({ url: `${base}/${id}/products/${itemId}/retry`, method: 'post' })
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

export const getPictureDetail = (id, itemId, index) => request({ url: `${base}/${id}/products/${itemId}/images/${index}/detail` })

export const retryFailedProducts = id => request({ url: `${base}/${id}/retry-failed`, method: 'post' })

export const getExecutionTask = listId => request({ url: `/product/scan/execution-lists/${listId}/task` })
export const resumeTask = id => request({ url: `${base}/${id}/resume`, method: 'post' })

export const recheckMatchedProducts = id => request({ url: `${base}/${id}/recheck-matched`, method: 'post' })
