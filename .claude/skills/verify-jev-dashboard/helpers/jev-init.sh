#!/usr/bin/env bash
# 用法：bash .claude/skills/verify-jev-dashboard/helpers/jev-init.sh <輸出的 .js 檔>
# 從 docs/testing/e2e-playbook.md 第 5 節抽出 window.jev helper，包成 agent-browser 的 init script。
# 劇本是唯一來源；helper 改了不用改這裡。
set -euo pipefail
out=${1:?要給輸出的 .js 檔路徑}
root=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
{
  echo '('
  awk '/^## 5\./ { s = 1 } s && /^```js\r?$/ { f = 1; next } f && /^```\r?$/ { exit } f' "$root/docs/testing/e2e-playbook.md"
  echo ')()'
} > "$out"
grep -q "jev helper ready" "$out" || { echo "劇本第 5 節找不到 helper，檢查 $out" >&2; exit 1; }
echo "$out"
