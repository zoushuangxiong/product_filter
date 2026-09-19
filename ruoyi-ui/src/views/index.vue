<template>
  <div class="app-container home">
    <el-card shadow="never">
      <h1>{{ title }}</h1>
      <p>欢迎使用商品筛选系统，请从导航菜单选择需要的功能。</p>
    </el-card>
  </div>
</template>

<script setup name="Index">
const title = import.meta.env.VITE_APP_TITLE
</script>

<style scoped>
.home h1 {
  margin: 0 0 16px;
  font-size: 24px;
  color: var(--el-text-color-primary);
}
.home p {
  margin: 0;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
}
</style>
