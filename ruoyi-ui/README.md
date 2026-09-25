# 商品过滤系统前端

基于 Vue 3、Vite、Element Plus 和 Pinia 的管理界面。

## 本地开发

```sh
npm install
npm run dev
```

开发服务默认使用 80 端口，`/dev-api` 请求代理到 `http://localhost:8080`，可在 `vite.config.js` 中调整。

## 构建与预览

```sh
npm run build:prod
npm run preview
```

测试环境构建使用 `npm run build:stage`，构建产物位于 `dist/`。
生产环境需配置 `/prod-api` 的后端转发，以及前端路由回退到 `index.html`。

## 配置

- `.env.development`、`.env.staging`、`.env.production`：页面标题和接口前缀。
- `src/settings.js`：主题、布局及页脚配置。
- `src/assets/logo/logo.svg`、`public/favicon.svg`：应用图标。

开源许可及原始版权声明见 `LICENSE` 和对应源码文件。
