import request from '@/utils/request'

const base = '/product/word-library'
export const listLibraries = params => request({ url: `${base}/list`, params })
export const libraryOptions = () => request({ url: `${base}/options` })
export const getLibrary = id => request({ url: `${base}/${id}` })
export const addLibrary = data => request({ url: base, method: 'post', data })
export const updateLibrary = data => request({ url: base, method: 'put', data })
export const deleteLibrary = id => request({ url: `${base}/${id}`, method: 'delete' })
