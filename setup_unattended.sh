#!/usr/bin/env bash
# 對所有「已授權」的 adb 裝置 (並行) 做無人值守設定：
#   1. 停用 ADB 授權逾時 (Android 11+ 預設 7 天沒連就撤銷授權)
#   2. 取消螢幕鎖定 (開機後不用解鎖，無障礙服務與授權視窗才能直接運作)
#
# 用法：
#   ./setup_unattended.sh                   # 所有已授權裝置
#   ./setup_unattended.sh -s SERIAL1 -s SERIAL2   # 指定裝置
#   ./setup_unattended.sh --pin 1234        # 手機有設 PIN/密碼時，提供舊密碼才能清除
#   ./setup_unattended.sh --keep-lock       # 只停用授權逾時，不動螢幕鎖定
#
# 注意：有設 PIN/圖形/密碼的手機，必須提供舊密碼才能清除 (Android 的限制)；
#       圖形鎖的密碼是點的編號，例如 1235789。
set -uo pipefail

PIN=""
KEEP_LOCK=0
SERIALS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s) SERIALS+=("$2"); shift 2 ;;
    --pin) PIN="$2"; shift 2 ;;
    --keep-lock) KEEP_LOCK=1; shift ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "未知參數: $1" >&2; exit 1 ;;
  esac
done

if [[ ${#SERIALS[@]} -eq 0 ]]; then
  while read -r serial state; do
    [[ "$state" == "device" ]] && SERIALS+=("$serial")
  done < <(adb devices | tail -n +2)
fi

if [[ ${#SERIALS[@]} -eq 0 ]]; then
  echo "沒有已授權的裝置。未授權的裝置請先在手機上按允許 (或跑 adb_auth_watchdog.py)。"
  exit 1
fi

setup_one() {
  local s="$1" out=()
  a() { adb -s "$s" shell "$@" 2>&1 | tr -d '\r'; }
  local model sdk
  model=$(a getprop ro.product.model)
  sdk=$(a getprop ro.build.version.sdk)

  # 1. 停用 ADB 授權逾時 (0 = 永不逾時)
  if [[ "$sdk" -ge 30 ]]; then
    a settings put global adb_allowed_connection_time 0 >/dev/null
    out+=("授權逾時=$(a settings get global adb_allowed_connection_time)")
  else
    out+=("授權逾時=不適用(Android 11 以下無此機制)")
  fi

  # 2. 取消螢幕鎖定
  if [[ $KEEP_LOCK -eq 0 ]]; then
    local old=() res
    [[ -n "$PIN" ]] && old=(--old "$PIN")
    # 有密碼的話先清除 (沒密碼時 clear 會回報錯誤，忽略即可)
    res=$(a locksettings clear ${old[@]+"${old[@]}"})
    if echo "$res" | grep -qiE "old password|incorrect|wrong|requires"; then
      out+=("螢幕鎖定=失敗(手機有密碼，請用 --pin 提供舊密碼)")
    else
      a locksettings set-disabled true >/dev/null
      if [[ "$(a locksettings get-disabled)" == "true" ]]; then
        out+=("螢幕鎖定=已取消")
      else
        out+=("螢幕鎖定=失敗: $res")
      fi
    fi
  fi

  echo "[$s] $model (SDK $sdk): ${out[*]}"
}

echo "設定 ${#SERIALS[@]} 台裝置..."
for s in "${SERIALS[@]}"; do
  setup_one "$s" &
done
wait
echo "完成。"
