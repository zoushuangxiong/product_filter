// 兼容后端初始化数据，排除框架自带的推广入口和演示公告。
function isTemplateLink(value) {
  if (typeof value !== 'string') return false
  try {
    const host = new URL(value).hostname.toLowerCase()
    return host === 'ruoyi.vip' || host.endsWith('.ruoyi.vip')
  } catch {
    return false
  }
}

export function filterProjectRoutes(routes) {
  return routes
    .filter(route => !isTemplateLink(route.path) && !isTemplateLink(route.meta?.link))
    .map(route => ({
      ...route,
      ...(route.children ? { children: filterProjectRoutes(route.children) } : {})
    }))
}

const demoNoticeTitles = new Set([
  '温馨提醒：2018-07-01 若依新版本发布啦',
  '维护通知：2018-07-01 若依系统凌晨维护',
  '若依开源框架介绍'
])

export function isProjectNotice(notice) {
  return !demoNoticeTitles.has(notice.noticeTitle)
}
