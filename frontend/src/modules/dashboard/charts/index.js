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
