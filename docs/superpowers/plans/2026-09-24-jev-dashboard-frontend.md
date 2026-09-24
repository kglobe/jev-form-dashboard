# JEV 儀表板前端 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做出 spec `docs/superpowers/specs/2026-09-23-jev-dashboard-design.md` 的 Vue 3 前端：接資料、觸發分析並預覽、存成個人儀表板、拖拉縮放卡片、顯示異常標註。

**Architecture:** 儀表板相關程式碼全放在 `frontend/src/modules/dashboard/`，整個資料夾可以直接搬到其他用 Vuetify 3 的專案。八種圖各有一個純函式 `toOption(chartData, mapping)`，負責把後端的 `ChartData` 轉成 ECharts option（表格是轉成表頭和資料列），這部分用 Vitest 測試。元件只負責呼叫 API 和排版。

**Tech Stack:** Vue 3.5、Vite 8、Vuetify 3.13、ECharts 6 + vue-echarts 8、grid-layout-plus 1.1、Vitest 5。

**前置條件：** 後端計畫 `docs/superpowers/plans/2026-09-24-jev-dashboard-backend.md` 已完成，`http://localhost:8080` 可以跑起來。Task 1 到 Task 4 不需要後端；Task 5 需要。

## Global Constraints

- Vue 3、Vite、Vuetify 3（不要裝 Vuetify 4）、ECharts（vue-echarts）、grid-layout-plus。
- 儀表板程式碼全放在 `frontend/src/modules/dashboard/`，不能依賴這個資料夾以外的檔案。
- 每個 API 請求都帶 `X-User-Id` header，值取自 `VITE_USER_ID`，預設 `demo-user`。
- 格線 12 欄，後端產生的 `gridPos` 一張卡片 `w: 6, h: 4`，明細表 `w: 12, h: 5`。
- 拖拉或縮放結束後才把 `gridPos` 存回後端（GridItem 的 `moved`、`resized` 事件）；預覽模式不能拖拉，也不存檔。
- 圖表先畫，再呼叫 `/api/jev/highlight/{id}`；異常檢查失敗時不顯示標註，也不顯示錯誤。
- 配置只有明細表時，顯示「找不到適合的圖表」。
- `ChartData.error === "FIELD_MISSING"` 時，卡片顯示「欄位遺失：{missingField}」，其他卡片照常顯示。
- 本機是 Windows + Git Bash，Node 23；指令都在 Git Bash 裡跑。

## 後端 API 介面（前端依賴的部分）

| 方法 | 路徑 | body | 回傳 |
|---|---|---|---|
| GET | `/api/datasources` | | `Datasource[]` |
| POST | `/api/datasources/upload` | multipart：`file`、`name`、`sheet`（選填） | `Datasource` |
| POST | `/api/datasources/api` | `{name, url, headers}` | `Datasource` |
| PATCH | `/api/datasources/{id}/sensitive` | `{fields: string[]}` | `Datasource` |
| POST | `/api/jev/analyze` | `{datasourceId}` | `DashboardConfig`（沒有 id） |
| GET | `/api/jev/highlight/{dashboardId}` | | `[{chartId, severity, noul}]` |
| GET | `/api/dashboards` | | `[{id, name, datasourceId, updatedAt}]` |
| POST | `/api/dashboards` | `DashboardConfig` | `DashboardConfig` |
| GET / PUT / DELETE | `/api/dashboards/{id}` | PUT 帶 `DashboardConfig` | `DashboardConfig`；DELETE 回 204 |
| GET | `/api/dashboards/{id}/data` | | `ChartData[]`，順序同 `layout` |
| POST | `/api/dashboards/preview/data` | `DashboardConfig` | `ChartData[]` |

- `Datasource`：`{id, name, kind, schema: [{name, type, distinctCount, samples, sensitive, min, max}], apiUrl, apiHeaders, createdBy, createdAt}`
- `DashboardConfig`：`{id, name, datasourceId, createdBy, layout: [{chartId, type, title, gridPos: {x,y,w,h}, mapping, options}]}`
- `ChartData`：`{chartId, type, dimensions, rows}`，或 `{chartId, type, error: "FIELD_MISSING", missingField}`
- `rows` 格式：bar/line/pie 是 `[x, value]`；stacked_bar 是 `[x, series, value]`；heatmap 是 `[x, y, value]`；radar 是 `[series, m1, m2, ...]`；scatter 是 `[x, y]`；table 是 `columns` 各欄的值
- 錯誤回應：`{error: "訊息"}`

## File Structure

```
frontend/
  package.json, package-lock.json, vite.config.js, index.html
  src/main.js, src/App.vue
  src/modules/dashboard/
    index.js              對外匯出元件和 api
    api.js                fetch 包裝，帶 X-User-Id
    charts/bar.js, line.js, pie.js, scatter.js, table.js, radar.js, heatmap.js, stackedBar.js
    charts/index.js       type → toOption
    charts/charts.test.js
    ChartCard.vue         單張卡片：標題、標註、圖表或表格
    DashboardView.vue     格線版面、載入資料、存 gridPos、載入標註
    DatasourcePanel.vue   上傳檔案、註冊 API、勾選敏感欄位
    AnalyzeButton.vue     觸發分析、全螢幕預覽、存為我的儀表板
```

---

### Task 1: 專案骨架與四種基本圖

**Files:**
- Create: `frontend/package.json`、`frontend/vite.config.js`、`frontend/index.html`、`frontend/src/main.js`、`frontend/src/App.vue`
- Create: `frontend/src/modules/dashboard/charts/bar.js`、`line.js`、`pie.js`、`scatter.js`、`index.js`
- Test: `frontend/src/modules/dashboard/charts/charts.test.js`

**Interfaces:**
- Produces: `toOption` 物件，`toOption[type](chartData, mapping) → EChartsOption`；這個 task 先放 `bar`、`line`、`pie`、`scatter` 四種。

- [ ] **Step 1: 建立專案、安裝套件**

`frontend/package.json`：

```json
{
  "name": "jev-dashboard-frontend",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "test": "vitest run"
  }
}
```

```bash
cd frontend
npm install vue@^3.5 vuetify@^3.13 @mdi/font echarts@^6 vue-echarts@^8 grid-layout-plus@^1.1
npm install -D vite@^8 @vitejs/plugin-vue@^6 vite-plugin-vuetify@^2 vitest@^5
```

Expected: 安裝成功，`package.json` 出現 `dependencies`、`devDependencies`，並產生 `package-lock.json`。確認 `npm ls vuetify` 顯示的是 `3.x`。

`frontend/vite.config.js`：

```js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'

export default defineConfig({
  plugins: [vue(), vuetify({ autoImport: true })],
  server: {
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    environment: 'node',
  },
})
```

`frontend/index.html`：

```html
<!doctype html>
<html lang="zh-Hant-TW">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>JEV 儀表板</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.js"></script>
  </body>
</html>
```

`frontend/src/main.js`：

```js
import { createApp } from 'vue'
import 'vuetify/styles'
import '@mdi/font/css/materialdesignicons.css'
import { createVuetify } from 'vuetify'
import App from './App.vue'

createApp(App).use(createVuetify()).mount('#app')
```

`frontend/src/App.vue`（Task 4 會換成完整版）：

```vue
<template>
  <v-app>
    <v-main><v-container>JEV 儀表板</v-container></v-main>
  </v-app>
</template>
```

Run: `cd frontend && npm run build`
Expected: 成功，產生 `frontend/dist/`

- [ ] **Step 2: 寫會失敗的測試**

`frontend/src/modules/dashboard/charts/charts.test.js`：

```js
import { describe, it, expect } from 'vitest'
import { toOption } from './index.js'

const byMonth = { chartId: 'c1', type: 'line', dimensions: ['order_date', 'amount'], rows: [['2024-01', 2000], ['2024-02', 2100]] }
const byRegion = { chartId: 'c2', type: 'bar', dimensions: ['region', 'amount'], rows: [['北區', 7400], ['南區', 2400]] }
const points = { chartId: 'c3', type: 'scatter', dimensions: ['amount', 'qty'], rows: [[1200, 3], [800, 2]] }

describe('toOption', () => {
  it('bar', () => {
    const o = toOption.bar(byRegion, { x: 'region', value: 'amount' })
    expect(o.dataset.source).toEqual(byRegion.rows)
    expect(o.series[0].type).toBe('bar')
    expect(o).toMatchSnapshot()
  })

  it('line', () => {
    const o = toOption.line(byMonth, { x: 'order_date', value: 'amount', timeGrain: 'month' })
    expect(o.series[0].type).toBe('line')
    expect(o).toMatchSnapshot()
  })

  it('pie', () => {
    const o = toOption.pie(byRegion, { x: 'region', value: 'amount' })
    expect(o.series[0].encode).toEqual({ itemName: 0, value: 1 })
    expect(o).toMatchSnapshot()
  })

  it('scatter', () => {
    const o = toOption.scatter(points, { x: 'amount', y: 'qty' })
    expect(o.xAxis).toEqual({ type: 'value', name: 'amount' })
    expect(o.yAxis).toEqual({ type: 'value', name: 'qty' })
    expect(o).toMatchSnapshot()
  })
})
```

- [ ] **Step 3: 跑測試，確認失敗**

Run: `cd frontend && npx vitest run src/modules/dashboard/charts`
Expected: FAIL，`Failed to resolve import "./index.js"`

- [ ] **Step 4: 實作**

`frontend/src/modules/dashboard/charts/bar.js`：

```js
export function toOption(data) {
  return {
    dataset: { dimensions: data.dimensions, source: data.rows },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category' },
    yAxis: { type: 'value' },
    series: [{ type: 'bar', encode: { x: 0, y: 1 } }],
  }
}
```

`frontend/src/modules/dashboard/charts/line.js`：

```js
export function toOption(data) {
  return {
    dataset: { dimensions: data.dimensions, source: data.rows },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category' },
    yAxis: { type: 'value' },
    series: [{ type: 'line', encode: { x: 0, y: 1 } }],
  }
}
```

`frontend/src/modules/dashboard/charts/pie.js`：

```js
export function toOption(data) {
  return {
    dataset: { dimensions: data.dimensions, source: data.rows },
    tooltip: { trigger: 'item' },
    legend: { type: 'scroll', bottom: 0 },
    series: [{ type: 'pie', radius: '60%', encode: { itemName: 0, value: 1 } }],
  }
}
```

`frontend/src/modules/dashboard/charts/scatter.js`：

```js
export function toOption(data, mapping) {
  return {
    dataset: { dimensions: data.dimensions, source: data.rows },
    tooltip: { trigger: 'item' },
    xAxis: { type: 'value', name: mapping.x },
    yAxis: { type: 'value', name: mapping.y },
    series: [{ type: 'scatter', encode: { x: 0, y: 1 } }],
  }
}
```

`frontend/src/modules/dashboard/charts/index.js`：

```js
import * as bar from './bar.js'
import * as line from './line.js'
import * as pie from './pie.js'
import * as scatter from './scatter.js'

export const toOption = {
  bar: bar.toOption,
  line: line.toOption,
  pie: pie.toOption,
  scatter: scatter.toOption,
}
```

- [ ] **Step 5: 跑測試，確認通過**

Run: `cd frontend && npx vitest run src/modules/dashboard/charts`
Expected: PASS（4 個測試），並產生 `charts/__snapshots__/charts.test.js.snap`

- [ ] **Step 6: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/vite.config.js frontend/index.html frontend/src
git commit -m "feat(frontend): Vite + Vuetify skeleton, bar/line/pie/scatter toOption"
```

---

### Task 2: 表格、雷達、熱力、堆疊長條

**Files:**
- Create: `frontend/src/modules/dashboard/charts/table.js`、`radar.js`、`heatmap.js`、`stackedBar.js`
- Modify: `frontend/src/modules/dashboard/charts/index.js`
- Modify test: `frontend/src/modules/dashboard/charts/charts.test.js`

**Interfaces:**
- Consumes: `toOption`（Task 1）
- Produces: `toOption.table(data) → {headers: string[], rows: any[][]}`（不是 ECharts option，ChartCard 用 `v-table` 畫）；`toOption.radar`、`toOption.heatmap`、`toOption.stacked_bar` 回傳 ECharts option。

- [ ] **Step 1: 寫會失敗的測試**

在 `charts.test.js` 的 `describe('toOption', ...)` 裡面、最後一個 `it` 後面加：

```js
  it('table returns headers and rows for v-table', () => {
    const data = { chartId: 'c4', type: 'table', dimensions: ['order_date', 'region'], rows: [['2024-01-05', '北區'], ['2024-01-18', null]] }
    expect(toOption.table(data)).toEqual({ headers: ['order_date', 'region'], rows: data.rows })
  })

  it('radar gives each measure its own axis max', () => {
    const data = { chartId: 'c5', type: 'radar', dimensions: ['region', 'amount', 'qty'], rows: [['北區', 100, 4], ['南區', 50, 6]] }
    const o = toOption.radar(data, { series: 'region', measures: ['amount', 'qty'] })
    expect(o.radar.indicator).toEqual([{ name: 'amount', max: 110.00000000000001 }, { name: 'qty', max: 6.6000000000000005 }])
    expect(o.series[0].data).toEqual([{ name: '北區', value: [100, 4] }, { name: '南區', value: [50, 6] }])
    expect(o).toMatchSnapshot()
  })

  it('radar axis max is 1 when every value is 0', () => {
    const data = { dimensions: ['region', 'amount'], rows: [['北區', 0]] }
    expect(toOption.radar(data, {}).radar.indicator[0].max).toBe(1)
  })

  it('heatmap turns long rows into index triples', () => {
    const data = { chartId: 'c6', type: 'heatmap', dimensions: ['region', 'product', 'amount'], rows: [['北', 'A', 100], ['南', 'A', 50], ['北', 'B', 200]] }
    const o = toOption.heatmap(data, { x: 'region', y: 'product', value: 'amount' })
    expect(o.xAxis.data).toEqual(['北', '南'])
    expect(o.yAxis.data).toEqual(['A', 'B'])
    expect(o.series[0].data).toEqual([[0, 0, 100], [1, 0, 50], [0, 1, 200]])
    expect(o.visualMap.min).toBe(0)
    expect(o.visualMap.max).toBe(200)
    expect(o).toMatchSnapshot()
  })

  it('stacked_bar pivots long rows into one series per group and fills gaps with 0', () => {
    const data = { chartId: 'c7', type: 'stacked_bar', dimensions: ['order_date', 'region', 'amount'], rows: [['2024-01', '北', 10], ['2024-01', '南', 20], ['2024-02', '北', 5]] }
    const o = toOption.stacked_bar(data, { x: 'order_date', series: 'region', value: 'amount' })
    expect(o.xAxis.data).toEqual(['2024-01', '2024-02'])
    expect(o.series.map((s) => [s.name, s.stack, s.data])).toEqual([['北', 'total', [10, 5]], ['南', 'total', [20, 0]]])
    expect(o).toMatchSnapshot()
  })
```

- [ ] **Step 2: 跑測試，確認失敗**

Run: `cd frontend && npx vitest run src/modules/dashboard/charts`
Expected: FAIL，`toOption.table is not a function`

- [ ] **Step 3: 實作**

`frontend/src/modules/dashboard/charts/table.js`：

```js
export function toOption(data) {
  return { headers: data.dimensions, rows: data.rows }
}
```

`frontend/src/modules/dashboard/charts/radar.js`：

```js
export function toOption(data) {
  const measures = data.dimensions.slice(1)
  const max = measures.map((_, i) => Math.max(...data.rows.map((r) => r[i + 1] ?? 0)) * 1.1 || 1)
  return {
    tooltip: {},
    legend: { type: 'scroll', bottom: 0 },
    radar: { indicator: measures.map((name, i) => ({ name, max: max[i] })) },
    series: [{ type: 'radar', data: data.rows.map((r) => ({ name: String(r[0]), value: r.slice(1) })) }],
  }
}
```

`frontend/src/modules/dashboard/charts/heatmap.js`：

```js
export function toOption(data) {
  const xs = [...new Set(data.rows.map((r) => r[0]))]
  const ys = [...new Set(data.rows.map((r) => r[1]))]
  const values = data.rows.map((r) => r[2])
  return {
    tooltip: { position: 'top' },
    grid: { bottom: 60 },
    xAxis: { type: 'category', data: xs },
    yAxis: { type: 'category', data: ys },
    visualMap: {
      min: Math.min(0, ...values),
      max: Math.max(0, ...values),
      calculable: true,
      orient: 'horizontal',
      left: 'center',
      bottom: 0,
    },
    series: [{ type: 'heatmap', data: data.rows.map((r) => [xs.indexOf(r[0]), ys.indexOf(r[1]), r[2]]) }],
  }
}
```

`frontend/src/modules/dashboard/charts/stackedBar.js`：

```js
// ponytail: 每格用 find 找值，O(列數 × 格數)；後端 series 最多 10 個，夠用
export function toOption(data) {
  const xs = [...new Set(data.rows.map((r) => r[0]))]
  const names = [...new Set(data.rows.map((r) => r[1]))]
  return {
    tooltip: { trigger: 'axis' },
    legend: { type: 'scroll', bottom: 0 },
    xAxis: { type: 'category', data: xs },
    yAxis: { type: 'value' },
    series: names.map((name) => ({
      name: String(name),
      type: 'bar',
      stack: 'total',
      data: xs.map((x) => data.rows.find((r) => r[0] === x && r[1] === name)?.[2] ?? 0),
    })),
  }
}
```

`frontend/src/modules/dashboard/charts/index.js` 改成：

```js
import * as bar from './bar.js'
import * as line from './line.js'
import * as pie from './pie.js'
import * as scatter from './scatter.js'
import * as table from './table.js'
import * as radar from './radar.js'
import * as heatmap from './heatmap.js'
import * as stackedBar from './stackedBar.js'

export const toOption = {
  bar: bar.toOption,
  line: line.toOption,
  pie: pie.toOption,
  scatter: scatter.toOption,
  table: table.toOption,
  radar: radar.toOption,
  heatmap: heatmap.toOption,
  stacked_bar: stackedBar.toOption,
}
```

- [ ] **Step 4: 跑測試，確認通過**

Run: `cd frontend && npx vitest run src/modules/dashboard/charts`
Expected: PASS（10 個測試）

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): table/radar/heatmap/stacked_bar toOption"
```

---

### Task 3: API client、ChartCard、DashboardView

**Files:**
- Create: `frontend/src/modules/dashboard/api.js`、`ChartCard.vue`、`DashboardView.vue`

**Interfaces:**
- Consumes: `toOption`（Task 2）；後端 API（見上方表格）
- Produces:
  - `api` 物件：`listDatasources()`、`uploadDatasource(FormData)`、`registerApi({name, url, headers})`、`setSensitive(id, fields)`、`analyze(datasourceId)`、`listDashboards()`、`getDashboard(id)`、`createDashboard(config)`、`updateDashboard(id, config)`、`deleteDashboard(id)`、`dashboardData(id)`、`previewData(config)`、`highlights(id)`。都回傳 Promise；非 2xx 時 reject 一個 `Error`，`message` 是後端的 `error` 欄位。
  - `<ChartCard :spec :data :highlight />`
  - `<DashboardView :config preview? />`：`preview` 為 true 時用 `previewData`、不能拖拉、不存檔、不呼叫 highlight。

- [ ] **Step 1: 實作 `api.js`**

`frontend/src/modules/dashboard/api.js`：

```js
const USER_ID = import.meta.env.VITE_USER_ID || 'demo-user'

async function request(method, url, body) {
  const isForm = body instanceof FormData
  const headers = { 'X-User-Id': USER_ID }
  if (body != null && !isForm) headers['Content-Type'] = 'application/json'
  const res = await fetch(url, {
    method,
    headers,
    body: body == null ? undefined : isForm ? body : JSON.stringify(body),
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err.error || `HTTP ${res.status}`)
  }
  return res.status === 204 ? null : res.json()
}

export const api = {
  listDatasources: () => request('GET', '/api/datasources'),
  uploadDatasource: (form) => request('POST', '/api/datasources/upload', form),
  registerApi: (body) => request('POST', '/api/datasources/api', body),
  setSensitive: (id, fields) => request('PATCH', `/api/datasources/${id}/sensitive`, { fields }),
  analyze: (datasourceId) => request('POST', '/api/jev/analyze', { datasourceId }),
  listDashboards: () => request('GET', '/api/dashboards'),
  getDashboard: (id) => request('GET', `/api/dashboards/${id}`),
  createDashboard: (config) => request('POST', '/api/dashboards', config),
  updateDashboard: (id, config) => request('PUT', `/api/dashboards/${id}`, config),
  deleteDashboard: (id) => request('DELETE', `/api/dashboards/${id}`),
  dashboardData: (id) => request('GET', `/api/dashboards/${id}/data`),
  previewData: (config) => request('POST', '/api/dashboards/preview/data', config),
  highlights: (id) => request('GET', `/api/jev/highlight/${id}`),
}
```

- [ ] **Step 2: 實作 `ChartCard.vue`**

`frontend/src/modules/dashboard/ChartCard.vue`：

```vue
<script setup>
import { computed } from 'vue'
import 'echarts'
import VChart from 'vue-echarts'
import { toOption } from './charts/index.js'

const props = defineProps({
  spec: { type: Object, required: true },
  data: { type: Object, default: null },
  highlight: { type: Object, default: null },
})

const SEVERITY_COLOR = { 正常波動: 'success', 值得注意: 'info', 需要關注: 'warning', 嚴重警示: 'error' }

const ready = computed(() => props.data && !props.data.error)
const table = computed(() => (ready.value && props.spec.type === 'table' ? toOption.table(props.data) : null))
const option = computed(() =>
  ready.value && props.spec.type !== 'table' ? toOption[props.spec.type](props.data, props.spec.mapping) : null,
)
</script>

<template>
  <v-card class="fill-height d-flex flex-column">
    <v-card-title class="card-drag d-flex align-center text-subtitle-1">
      <span class="text-truncate">{{ spec.title }}</span>
      <v-spacer />
      <v-chip v-if="highlight" size="small" :color="SEVERITY_COLOR[highlight.severity]">
        {{ highlight.severity }}
      </v-chip>
    </v-card-title>
    <v-card-text class="flex-grow-1 card-body">
      <div v-if="!data" class="text-medium-emphasis">載入中…</div>
      <v-alert v-else-if="data.error === 'FIELD_MISSING'" type="warning" density="compact">
        欄位遺失：{{ data.missingField }}
      </v-alert>
      <v-table v-else-if="table" density="compact" fixed-header height="100%">
        <thead>
          <tr><th v-for="h in table.headers" :key="h">{{ h }}</th></tr>
        </thead>
        <tbody>
          <tr v-for="(row, i) in table.rows" :key="i">
            <td v-for="(cell, j) in row" :key="j">{{ cell }}</td>
          </tr>
        </tbody>
      </v-table>
      <VChart v-else :option="option" autoresize />
    </v-card-text>
  </v-card>
</template>

<style scoped>
.card-drag { cursor: move; }
.card-body { min-height: 0; }
</style>
```

- [ ] **Step 3: 實作 `DashboardView.vue`**

`frontend/src/modules/dashboard/DashboardView.vue`：

```vue
<script setup>
import { computed, ref, watch } from 'vue'
import { GridItem, GridLayout } from 'grid-layout-plus'
import ChartCard from './ChartCard.vue'
import { api } from './api.js'

const props = defineProps({
  config: { type: Object, required: true },
  preview: { type: Boolean, default: false },
})

const layout = ref([])
const dataById = ref({})
const highlightById = ref({})
const error = ref('')

const specById = computed(() => Object.fromEntries(props.config.layout.map((s) => [s.chartId, s])))
const onlyTable = computed(() => props.config.layout.every((s) => s.type === 'table'))

async function load() {
  layout.value = props.config.layout.map((s) => ({ i: s.chartId, ...s.gridPos }))
  dataById.value = {}
  highlightById.value = {}
  error.value = ''
  try {
    const data = props.preview ? await api.previewData(props.config) : await api.dashboardData(props.config.id)
    dataById.value = Object.fromEntries(data.map((d) => [d.chartId, d]))
  } catch (e) {
    error.value = e.message
    return
  }
  if (props.preview) return
  // 異常檢查失敗就不標註，不顯示錯誤
  const found = await api.highlights(props.config.id).catch(() => [])
  highlightById.value = Object.fromEntries(found.map((h) => [h.chartId, h]))
}

watch(() => props.config, load, { immediate: true })

async function saveLayout() {
  if (props.preview) return
  const pos = Object.fromEntries(layout.value.map((l) => [l.i, { x: l.x, y: l.y, w: l.w, h: l.h }]))
  const next = { ...props.config, layout: props.config.layout.map((s) => ({ ...s, gridPos: pos[s.chartId] })) }
  await api.updateDashboard(props.config.id, next).catch((e) => (error.value = e.message))
}
</script>

<template>
  <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
  <v-alert v-if="onlyTable" type="info" class="mb-4">找不到適合的圖表</v-alert>
  <GridLayout
    v-model:layout="layout"
    :col-num="12"
    :row-height="80"
    :is-draggable="!preview"
    :is-resizable="!preview"
  >
    <GridItem
      v-for="item in layout"
      :key="item.i"
      :i="item.i"
      :x="item.x"
      :y="item.y"
      :w="item.w"
      :h="item.h"
      drag-allow-from=".card-drag"
      @moved="saveLayout"
      @resized="saveLayout"
    >
      <ChartCard :spec="specById[item.i]" :data="dataById[item.i]" :highlight="highlightById[item.i]" />
    </GridItem>
  </GridLayout>
</template>
```

- [ ] **Step 4: 確認能 build**

Run: `cd frontend && npm run build`
Expected: 成功。元件還沒被 App 用到，這一步只確認語法和 import 都正確。

Run: `cd frontend && npm test`
Expected: PASS（10 個測試）

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): api client, ChartCard, DashboardView with draggable grid"
```

---

### Task 4: 資料源面板、分析按鈕、主畫面

**Files:**
- Create: `frontend/src/modules/dashboard/DatasourcePanel.vue`、`AnalyzeButton.vue`、`index.js`
- Modify: `frontend/src/App.vue`（整個換掉）

**Interfaces:**
- Consumes: `api`、`DashboardView`（Task 3）
- Produces:
  - `<DatasourcePanel :selected-id @select="id => ..." />`
  - `<AnalyzeButton :datasource-id @saved="id => ..." />`
  - `src/modules/dashboard/index.js` 匯出 `DashboardView`、`ChartCard`、`DatasourcePanel`、`AnalyzeButton`、`api`、`toOption`

- [ ] **Step 1: 實作 `DatasourcePanel.vue`**

`frontend/src/modules/dashboard/DatasourcePanel.vue`：

```vue
<script setup>
import { onMounted, ref } from 'vue'
import { api } from './api.js'

defineProps({ selectedId: { type: String, default: null } })
const emit = defineEmits(['select'])

const tab = ref('upload')
const list = ref([])
const error = ref('')
const busy = ref(false)

const file = ref(null)
const name = ref('')
const sheet = ref('')

const apiName = ref('')
const apiUrl = ref('')
const apiHeaders = ref('{}')

async function refresh() {
  list.value = await api.listDatasources()
}

onMounted(() => refresh().catch((e) => (error.value = e.message)))

async function run(action) {
  error.value = ''
  busy.value = true
  try {
    const ds = await action()
    await refresh()
    emit('select', ds.id)
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

function upload() {
  // v-file-input 在不同版本可能回傳 File 或 File[]
  const f = Array.isArray(file.value) ? file.value[0] : file.value
  if (!f) return
  const form = new FormData()
  form.append('file', f)
  form.append('name', name.value || f.name)
  if (sheet.value) form.append('sheet', sheet.value)
  return run(() => api.uploadDatasource(form))
}

function register() {
  return run(() => api.registerApi({ name: apiName.value, url: apiUrl.value, headers: JSON.parse(apiHeaders.value || '{}') }))
}

async function toggleSensitive(ds, field, on) {
  const fields = ds.schema.filter((f) => (f.name === field ? on : f.sensitive)).map((f) => f.name)
  await api.setSensitive(ds.id, fields).catch((e) => (error.value = e.message))
  await refresh()
}
</script>

<template>
  <div class="pa-4">
    <v-tabs v-model="tab" density="compact" class="mb-2">
      <v-tab value="upload">上傳檔案</v-tab>
      <v-tab value="api">外部 API</v-tab>
    </v-tabs>
    <v-window v-model="tab">
      <v-window-item value="upload">
        <v-file-input v-model="file" label="CSV / Excel" accept=".csv,.xlsx,.xls" density="compact" />
        <v-text-field v-model="name" label="名稱（預設用檔名）" density="compact" />
        <v-text-field v-model="sheet" label="工作表（Excel 選填）" density="compact" />
        <v-btn block :loading="busy" @click="upload">上傳</v-btn>
      </v-window-item>
      <v-window-item value="api">
        <v-text-field v-model="apiName" label="名稱" density="compact" />
        <v-text-field v-model="apiUrl" label="網址" density="compact" />
        <v-textarea
          v-model="apiHeaders"
          label='headers（值只能寫 ${環境變數名}）'
          rows="2"
          density="compact"
        />
        <v-btn block :loading="busy" @click="register">註冊</v-btn>
      </v-window-item>
    </v-window>

    <v-alert v-if="error" type="error" density="compact" class="mt-2">{{ error }}</v-alert>

    <v-list density="compact" class="mt-2">
      <v-list-subheader>資料源</v-list-subheader>
      <v-list-group v-for="ds in list" :key="ds.id" :value="ds.id">
        <template #activator="{ props: activator }">
          <v-list-item
            v-bind="activator"
            :title="ds.name"
            :subtitle="ds.kind === 'api' ? ds.apiUrl : `${ds.schema.length} 個欄位`"
            :active="ds.id === selectedId"
            @click="emit('select', ds.id)"
          />
        </template>
        <v-list-item v-for="f in ds.schema" :key="f.name" density="compact">
          <v-checkbox
            :model-value="f.sensitive"
            :label="`${f.name}（${f.type}）不送樣本`"
            density="compact"
            hide-details
            @update:model-value="(on) => toggleSensitive(ds, f.name, on)"
          />
        </v-list-item>
      </v-list-group>
    </v-list>
  </div>
</template>
```

- [ ] **Step 2: 實作 `AnalyzeButton.vue`**

`frontend/src/modules/dashboard/AnalyzeButton.vue`：

```vue
<script setup>
import { ref } from 'vue'
import { api } from './api.js'
import DashboardView from './DashboardView.vue'

const props = defineProps({ datasourceId: { type: String, default: null } })
const emit = defineEmits(['saved'])

const preview = ref(null)
const open = ref(false)
const busy = ref(false)
const error = ref('')

async function analyze() {
  busy.value = true
  error.value = ''
  try {
    preview.value = await api.analyze(props.datasourceId)
    open.value = true
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function save() {
  try {
    const saved = await api.createDashboard(preview.value)
    open.value = false
    emit('saved', saved.id)
  } catch (e) {
    error.value = e.message
  }
}
</script>

<template>
  <v-btn color="primary" block :loading="busy" :disabled="!datasourceId" @click="analyze">分析</v-btn>
  <v-alert v-if="error && !open" type="error" density="compact" class="mt-2">{{ error }}</v-alert>
  <v-dialog v-model="open" fullscreen>
    <v-card>
      <v-toolbar title="儀表板預覽">
        <v-text-field
          v-if="preview"
          v-model="preview.name"
          density="compact"
          hide-details
          class="mx-4"
          style="max-width: 320px"
        />
        <v-btn @click="open = false">取消</v-btn>
        <v-btn color="primary" variant="flat" class="mr-2" @click="save">存為我的儀表板</v-btn>
      </v-toolbar>
      <v-card-text>
        <v-alert v-if="error" type="error" density="compact" class="mb-2">{{ error }}</v-alert>
        <DashboardView v-if="preview" :config="preview" preview />
      </v-card-text>
    </v-card>
  </v-dialog>
</template>
```

- [ ] **Step 3: 模組匯出與主畫面**

`frontend/src/modules/dashboard/index.js`：

```js
export { default as DashboardView } from './DashboardView.vue'
export { default as ChartCard } from './ChartCard.vue'
export { default as DatasourcePanel } from './DatasourcePanel.vue'
export { default as AnalyzeButton } from './AnalyzeButton.vue'
export { api } from './api.js'
export { toOption } from './charts/index.js'
```

`frontend/src/App.vue`（整個換掉）：

```vue
<script setup>
import { onMounted, ref } from 'vue'
import { AnalyzeButton, DashboardView, DatasourcePanel, api } from './modules/dashboard/index.js'

const datasourceId = ref(null)
const dashboards = ref([])
const current = ref(null)
const error = ref('')

async function refresh() {
  dashboards.value = await api.listDashboards()
}

async function open(id) {
  current.value = await api.getDashboard(id)
}

async function onSaved(id) {
  await refresh()
  await open(id)
}

async function remove(id) {
  await api.deleteDashboard(id)
  if (current.value?.id === id) current.value = null
  await refresh()
}

onMounted(() => refresh().catch((e) => (error.value = e.message)))
</script>

<template>
  <v-app>
    <v-navigation-drawer permanent width="380">
      <DatasourcePanel :selected-id="datasourceId" @select="(id) => (datasourceId = id)" />
      <div class="px-4 pb-4">
        <AnalyzeButton :datasource-id="datasourceId" @saved="onSaved" />
      </div>
      <v-divider />
      <v-list density="compact">
        <v-list-subheader>我的儀表板</v-list-subheader>
        <v-list-item
          v-for="d in dashboards"
          :key="d.id"
          :title="d.name"
          :active="current?.id === d.id"
          @click="open(d.id)"
        >
          <template #append>
            <v-btn icon="mdi-delete" size="x-small" variant="text" @click.stop="remove(d.id)" />
          </template>
        </v-list-item>
      </v-list>
    </v-navigation-drawer>
    <v-main>
      <v-container fluid>
        <v-alert v-if="error" type="error" class="mb-4">{{ error }}</v-alert>
        <DashboardView v-if="current" :config="current" />
        <div v-else class="text-medium-emphasis">選一份儀表板，或上傳資料後按「分析」。</div>
      </v-container>
    </v-main>
  </v-app>
</template>
```

- [ ] **Step 4: 確認能 build、測試仍通過**

Run: `cd frontend && npm run build && npm test`
Expected: build 成功；PASS（10 個測試）

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): datasource panel, analyze preview, main screen"
```

---

### Task 5: 前後端整合驗證

**Files:**
- 不新增檔案。發現問題時修正對應檔案，並在 commit 訊息裡寫清楚改了什麼。

**Interfaces:**
- Consumes: 後端全部 API、前端全部元件

- [ ] **Step 1: 準備 JEV key**

**向使用者要 JEV API key**，填進 `backend/.env` 的 `TYPESAFE_API_KEY`。沒有 key 就只能驗證到第 4 步，並回報「分析」會拿到 503「未設定 TYPESAFE_API_KEY」。

- [ ] **Step 2: 啟動兩邊**

Git Bash 視窗一：

```bash
cd backend && ./mvnw -q spring-boot:run
```

Git Bash 視窗二：

```bash
cd frontend && npm run dev
```

Expected: 後端在 `8080`，前端在 Vite 印出的網址（通常是 `http://localhost:5173`）。

- [ ] **Step 3: 驗證上傳和敏感欄位**

用瀏覽器（或 chrome-devtools MCP）打開前端：

1. 在「上傳檔案」選 `backend/src/test/resources/sales.csv`，按「上傳」。
   Expected: 左側資料源清單出現「sales.csv」，副標題「6 個欄位」。
2. 展開它，勾選 `memo` 的「不送樣本」。
   Expected: 重新整理頁面後仍是勾選狀態。

- [ ] **Step 4: 驗證錯誤訊息**

1. 上傳一個副檔名是 `.txt` 的檔案。
   Expected: 顯示「只支援 .csv、.xlsx、.xls」。
2. 在「外部 API」填網址 `https://evil.example/x`，按「註冊」。
   Expected: 顯示「未開放註冊外部 API」（預設允許清單是空的）。

- [ ] **Step 5: 驗證分析、預覽、存檔**

1. 選 sales.csv，按「分析」。
   Expected: 幾秒內跳出全螢幕預覽，卡片可以看到圖表，最後一張是明細表；預覽裡不能拖拉。
2. 改名稱，按「存為我的儀表板」。
   Expected: 預覽關閉，「我的儀表板」清單出現新名稱，主畫面顯示這份儀表板。

- [ ] **Step 6: 驗證拖拉存檔和異常標註**

1. 拖動一張卡片的標題列到另一個位置，再縮放一張卡片。
2. 重新整理頁面，再打開同一份儀表板。
   Expected: 卡片位置和大小維持拖拉後的樣子。
3. 如果 JEV 判斷有異常，對應卡片右上角會出現嚴重程度的標籤；沒有標籤也是正常結果。打開瀏覽器 Network，確認 `/api/jev/highlight/...` 回 200。

- [ ] **Step 7: 驗證欄位遺失**

用 curl 建一份儀表板，其中一張卡片指向不存在的欄位：

```bash
DS=$(curl -s -H "X-User-Id: demo-user" http://localhost:8080/api/datasources | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>console.log(JSON.parse(s)[0].id))")
curl -s -H "X-User-Id: demo-user" -H "Content-Type: application/json" http://localhost:8080/api/dashboards -d "{\"name\":\"欄位遺失測試\",\"datasourceId\":\"$DS\",\"layout\":[{\"chartId\":\"c1\",\"type\":\"bar\",\"title\":\"利潤\",\"gridPos\":{\"x\":0,\"y\":0,\"w\":6,\"h\":4},\"mapping\":{\"x\":\"region\",\"value\":\"profit\"}},{\"chartId\":\"c2\",\"type\":\"pie\",\"title\":\"地區\",\"gridPos\":{\"x\":6,\"y\":0,\"w\":6,\"h\":4},\"mapping\":{\"x\":\"region\",\"value\":\"amount\"}}]}"
```

重新整理前端，打開「欄位遺失測試」。
Expected: c1 顯示「欄位遺失：profit」，c2 正常顯示圓餅圖。

- [ ] **Step 8: 回報並 commit 修正**

把每一步的結果（通過或看到什麼）整理回報。有修正的話：

```bash
git add frontend/src backend/src
git commit -m "fix: issues found in end-to-end verification"
```

沒有修正就不用 commit。
