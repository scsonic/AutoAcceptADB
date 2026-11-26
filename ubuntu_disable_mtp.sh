#!/usr/bin/env bash
# 讓 Ubuntu 插上 Android 手機時「不要」去開 MTP/PTP (檔案/照片傳輸) 連線，
# 手機就不會跳出「允許存取手機資料？」的詢問，只保留 ADB。
#
# 原理：手機的資料存取詢問是在電腦端開啟 MTP/PTP session 時才觸發的。
# Ubuntu 桌面版由 gvfs (mtp / gphoto2 volume monitor) 自動去開，
# 這裡把它們停掉，並用 udev 規則把 Android 裝置的 MTP/PTP 標記拿掉。
#
# 用法： sudo ./ubuntu_disable_mtp.sh          # 套用
#        sudo ./ubuntu_disable_mtp.sh --undo   # 還原
set -euo pipefail

RULE=/etc/udev/rules.d/99-android-no-mtp.rules
TARGET_USER="${SUDO_USER:-$USER}"

if [[ $EUID -ne 0 ]]; then
  echo "請用 sudo 執行" >&2
  exit 1
fi

user_systemctl() {
  local uid
  uid=$(id -u "$TARGET_USER")
  sudo -u "$TARGET_USER" XDG_RUNTIME_DIR="/run/user/$uid" systemctl --user "$@" 2>/dev/null || true
}

if [[ "${1:-}" == "--undo" ]]; then
  rm -f "$RULE"
  udevadm control --reload-rules
  user_systemctl unmask gvfs-mtp-volume-monitor.service gvfs-gphoto2-volume-monitor.service
  user_systemctl start gvfs-mtp-volume-monitor.service gvfs-gphoto2-volume-monitor.service
  echo "已還原 MTP/PTP 自動掛載"
  exit 0
fi

# 1. 停用 gvfs 的 MTP / PTP 自動偵測 (桌面版 Ubuntu 才有，伺服器版沒有也無妨)
user_systemctl mask --now gvfs-mtp-volume-monitor.service gvfs-gphoto2-volume-monitor.service
pkill -u "$TARGET_USER" -f gvfsd-mtp 2>/dev/null || true
pkill -u "$TARGET_USER" -f gvfsd-gphoto2 2>/dev/null || true

# 2. udev：清除所有 USB 裝置的 MTP / PTP 標記，讓 gvfs / KDE / udisks 等都不會去開 session
#    (只影響 MTP/PTP 自動偵測，不影響 adb、USB 隨身碟、相機以外的裝置)
cat > "$RULE" <<'EOF'
# Do not let desktop daemons open MTP/PTP sessions on Android phones
# (avoids the "Allow access to phone data?" prompt; adb is unaffected)
ACTION=="add|change", SUBSYSTEM=="usb", ENV{DEVTYPE}=="usb_device", ENV{ID_MTP_DEVICE}="", ENV{ID_GPHOTO2}="", ENV{ID_MEDIA_PLAYER}=""
EOF
udevadm control --reload-rules
udevadm trigger --subsystem-match=usb --action=change

echo "完成：已停用 MTP/PTP 自動偵測 (使用者: $TARGET_USER)。請拔插手機測試。"
echo "還原： sudo $0 --undo"
