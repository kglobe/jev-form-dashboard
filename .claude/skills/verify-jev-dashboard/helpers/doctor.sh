#!/usr/bin/env bash
# 用法：bash .claude/skills/verify-jev-dashboard/helpers/doctor.sh <後端 port> <前端 port>
# 只讀檢查：port 有沒有人在聽、jar 是不是最新、前端 proxy 有沒有接到後端、e2e-admin 登不登得進去。
# e2e-admin 只存在這套驗證環境的新 schema，登得進去就代表前端接的是自己開的後端，不是使用者的 8080。
# 全部 OK 時結束碼 0，有任何 FAIL 是 1。
B=${1:?後端 port}
F=${2:?前端 port}
root=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
bad=0
ok() { echo "OK    $*"; }
ng() { echo "FAIL  $*"; bad=1; }

for p in "$B" "$F"; do
  pid=$(netstat -ano | awk -v p=":$p" '$4 == "LISTENING" && substr($2, length($2) - length(p) + 1) == p { print $5; exit }')
  if [ -n "$pid" ]; then ok ":$p 在聽，PID $pid"; else ng ":$p 沒有在聽"; fi
done

jar_file="$root/backend/target/jev-dashboard-0.1.0-SNAPSHOT.jar"
if [ ! -f "$jar_file" ]; then
  ng "沒有 $jar_file，先跑劇本 S00-4"
elif [ -n "$(find "$root/backend/src" "$root/backend/pom.xml" -newer "$jar_file" -type f -print -quit)" ]; then
  ng "backend/src 有檔案比 jar 新，重跑 S00-4 再重開後端"
else
  ok "jar 比 backend/src 新（HEAD $(git -C "$root" rev-parse --short HEAD)）"
fi

opts=$(curl -s "http://localhost:$F/api/auth/options")
if [ "$opts" = '{"oidc":null}' ]; then ok "前端 proxy 接到後端：$opts"; else ng "前端 /api/auth/options 回「$opts」，要是 {\"oidc\":null}"; fi

cookies=$(mktemp)
trap 'rm -f "$cookies"' EXIT
curl -s -c "$cookies" -o /dev/null "http://localhost:$F/api/auth/me"
xsrf=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$cookies")
curl -s -b "$cookies" -c "$cookies" -o /dev/null -H "X-XSRF-TOKEN: $xsrf" \
  --data-urlencode username=e2e-admin --data-urlencode password=E2eAdmin-2026 "http://localhost:$F/api/auth/login"
me=$(curl -s -b "$cookies" "http://localhost:$F/api/auth/me")
case $me in
  *'"username":"e2e-admin"'*'"admin":true'*) ok "經前端登入 e2e-admin：$me" ;;
  *) ng "e2e-admin 登不進去，前端接的可能不是這次的後端：$me" ;;
esac

exit $bad
