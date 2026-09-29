// 按需引入：只註冊 charts/*.js 用得到的圖表和元件，不整包 import 'echarts'。
// 加新圖型或新元件（例如 dataZoom、markLine）要在這裡一起註冊，echarts.test.js 會抓漏
import { use } from 'echarts/core'
import { BarChart, HeatmapChart, LineChart, PieChart, RadarChart, ScatterChart } from 'echarts/charts'
import {
  DatasetComponent,
  GridComponent,
  LegendComponent,
  RadarComponent,
  TooltipComponent,
  VisualMapComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([
  BarChart,
  HeatmapChart,
  LineChart,
  PieChart,
  RadarChart,
  ScatterChart,
  DatasetComponent,
  GridComponent,
  LegendComponent,
  RadarComponent,
  TooltipComponent,
  VisualMapComponent,
  CanvasRenderer,
])
