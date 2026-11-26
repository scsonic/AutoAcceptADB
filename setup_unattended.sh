#!/usr/bin/env bash
# 對所有「已授權」的 adb 裝置 (並行) 做無人值守設定，讓手機插上任何新電腦都自動通過 ADB 授權：
#   1. 安裝 / 更新 AutoAccept ADB (--apk 指定，或沒裝時自動用 build 出來的 debug apk)
#   2. 開啟無障礙服務 + 電池白名單
#   3. 授予 READ_LOGS / WRITE_SECURE_SETTINGS (App 用來偵測並修復「授權詢問卡住」)
#   4. 停用 ADB 授權逾時 (Android 11+ 預設 7 天沒連就撤銷授權)
#   5. 取消螢幕鎖定 (鎖定時 Samsung 會暫緩授權詢問，且之後不再詢問)
#
# 用法：
#   ./setup_unattended.sh                   # 所有已授權裝置
#   ./setup_unattended.sh -s SERIAL1 -s SERIAL2   # 指定裝置
#   ./setup_unattended.sh --pin 1234        # 手機有設 PIN/密碼時，提供舊密碼才能清除
#   ./setup_unattended.sh --keep-lock       # 不動螢幕鎖定
#   ./setup_unattended.sh --apk path/to.apk # 指定要安裝的 APK (每台都會重新安裝)
#
# 注意：有設 PIN/圖形/密碼的手機，必須提供舊密碼才能清除 (Android 的限制)；
#       圖形鎖的密碼是點的編號，例如 1235789。
set -uo pipefail

PIN=""
KEEP_LOCK=0
APK=""
PKG=com.autoaccept.adb
SVC=com.autoaccept.adb/com.autoaccept.adb.AdbAccessibilityService
DEFAULT_APK="$(cd "$(dirname "$0")" && pwd)/app/build/outputs/apk/debug/app-debug.apk"
SERIALS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s) SERIALS+=("$2"); shift 2 ;;
    --pin) PIN="$2"; shift 2 ;;
    --keep-lock) KEEP_LOCK=1; shift ;;
    --apk) APK="$2"; shift 2 ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
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

  # 1. 安裝 App
  local installed apk="$APK"
  installed=$(a pm list packages "$PKG" | grep -c "package:$PKG\$")
  if [[ -z "$apk" && "$installed" -eq 0 ]]; then
    apk="$DEFAULT_APK"
  fi
  if [[ -n "$apk" ]]; then
    if [[ -f "$apk" ]] && adb -s "$s" install -r -g "$apk" >/dev/null 2>&1; then
      out+=("App=已安裝")
    else
      out+=("App=安裝失敗($apk)")
    fi
  else
    out+=("App=已存在")
  fi

  # 2. 無障礙服務 (保留其他已開啟的服務) + 電池白名單
  local cur
  cur=$(a settings get secure enabled_accessibility_services)
  if [[ "$cur" != *"$SVC"* && "$cur" != *"$PKG/.AdbAccessibilityService"* ]]; then
    if [[ -z "$cur" || "$cur" == "null" ]]; then cur="$SVC"; else cur="$cur:$SVC"; fi
    a settings put secure enabled_accessibility_services "$cur" >/dev/null
  fi
  a settings put secure accessibility_enabled 1 >/dev/null
  a dumpsys deviceidle whitelist +"$PKG" >/dev/null
  out+=("無障礙=$( [[ "$(a settings get secure enabled_accessibility_services)" == *AdbAccessibilityService* ]] && echo 開啟 || echo 失敗)")

  # 3. 自我修復權限
  a pm grant "$PKG" android.permission.READ_LOGS >/dev/null
  a pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS >/dev/null
  local perms
  perms=$(a dumpsys package "$PKG")
  if [[ "$perms" == *"WRITE_SECURE_SETTINGS: granted=true"* && "$perms" == *"READ_LOGS: granted=true"* ]]; then
    out+=("修復權限=OK")
  else
    out+=("修復權限=失敗")
  fi

  # 4. 停用 ADB 授權逾時 (0 = 永不逾時)
  if [[ "$sdk" -ge 30 ]]; then
    a settings put global adb_allowed_connection_time 0 >/dev/null
    out+=("授權逾時=$(a settings get global adb_allowed_connection_time)")
  else
    out+=("授權逾時=不適用(Android 11 以下無此機制)")
  fi

  # 5. 取消螢幕鎖定
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
