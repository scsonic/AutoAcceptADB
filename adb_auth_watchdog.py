#!/usr/bin/env python3
"""
ADB 授權看門狗：持續監控所有 adb 裝置，遇到 unauthorized / offline 的裝置就強制重新連線，
讓手機重新跳出「允許 USB 偵錯？」視窗，再由手機上的 AutoAccept ADB 自動按允許。

為什麼需要：授權視窗只在電腦端發起新連線時出現。如果視窗被取消
(例如 USB 重新列舉、MTP 視窗蓋掉、開機時還在鎖定畫面)，裝置會一直停在 unauthorized，
直到電腦重新連線為止。

設計 (多台裝置同時連線)：
  - 每台裝置各自計時：變成 unauthorized 後先等 GRACE 秒 (讓視窗和 App 有時間處理)，
    才重新連線；同一台裝置兩次重連之間至少間隔 COOLDOWN 秒，避免一直打斷正在顯示的視窗。
  - 多台裝置並行重連 (adb -s SERIAL reconnect)，單台失敗時退回 adb reconnect offline。
  - 狀態是 "authorizing" (正在詢問中) 的裝置不動。

用法：
  python3 adb_auth_watchdog.py                 # 持續監控
  python3 adb_auth_watchdog.py --once          # 只檢查並處理一次
  python3 adb_auth_watchdog.py --grace 20 --cooldown 45 --interval 5

注意：
  - 請用「平常跑 adb 的同一個使用者」執行。root 和一般使用者的 ~/.android/adbkey 不同，
    手機會把它們當成兩台不同的電腦。
  - 開機自動執行 (systemd user service) 範例，存成 ~/.config/systemd/user/adb-auth-watchdog.service：
        [Unit]
        Description=ADB authorization watchdog
        [Service]
        ExecStart=/usr/bin/python3 /path/to/adb_auth_watchdog.py
        Restart=always
        RestartSec=5
        [Install]
        WantedBy=default.target
    然後：systemctl --user daemon-reload && systemctl --user enable --now adb-auth-watchdog
          sudo loginctl enable-linger $USER   # 沒登入也會執行
"""
import argparse
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor

BAD_STATES = {"unauthorized", "offline"}


def log(msg):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), msg, flush=True)


def run(args, timeout=15):
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout + r.stderr).strip()
    except subprocess.TimeoutExpired:
        return -1, "timeout"
    except FileNotFoundError:
        log("找不到 adb，請確認已安裝並在 PATH 中")
        sys.exit(1)


def list_devices():
    """回傳 {serial: state}"""
    code, out = run(["adb", "devices"])
    devices = {}
    if code != 0:
        log(f"adb devices 失敗: {out}")
        return devices
    for line in out.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices") or line.startswith("*"):
            continue
        parts = line.split(None, 1)
        if len(parts) == 2:
            devices[parts[0]] = parts[1].strip()
    return devices


def kick(serial):
    code, out = run(["adb", "-s", serial, "reconnect"])
    return serial, code == 0, out


class Watchdog:
    def __init__(self, grace, cooldown, workers):
        self.grace = grace
        self.cooldown = cooldown
        self.workers = workers
        self.bad_since = {}    # serial -> 第一次看到 unauthorized/offline 的時間
        self.last_kick = {}    # serial -> 上次重連時間
        self.kick_count = {}   # serial -> 重連次數
        self.last_state = {}   # serial -> 上次狀態 (只在變化時印 log)
        self.last_global = 0.0

    def tick(self):
        now = time.time()
        devices = list_devices()

        # 狀態變化 log
        for serial, state in devices.items():
            prev = self.last_state.get(serial)
            if prev != state:
                if state == "device" and prev in BAD_STATES | {"authorizing"}:
                    n = self.kick_count.get(serial, 0)
                    log(f"✅ {serial} 已授權 (重連 {n} 次)")
                elif state.startswith("no permissions"):
                    log(f"⚠️  {serial} {state} — 請設定 udev 規則 (plugdev) 後重插")
                else:
                    log(f"   {serial}: {prev or '(新裝置)'} -> {state}")
        for serial in set(self.last_state) - set(devices):
            log(f"   {serial}: 已拔除")
            for d in (self.bad_since, self.last_kick, self.kick_count):
                d.pop(serial, None)
        self.last_state = dict(devices)

        # 找出需要重連的裝置
        to_kick = []
        for serial, state in devices.items():
            if state not in BAD_STATES:
                self.bad_since.pop(serial, None)
                if state == "device":
                    self.kick_count.pop(serial, None)
                    self.last_kick.pop(serial, None)
                continue
            since = self.bad_since.setdefault(serial, now)
            if now - since < self.grace:
                continue
            if now - self.last_kick.get(serial, 0) < self.cooldown:
                continue
            to_kick.append(serial)

        if not to_kick:
            return

        failed = []
        with ThreadPoolExecutor(max_workers=self.workers) as pool:
            for serial, ok, out in pool.map(kick, to_kick):
                self.last_kick[serial] = now
                self.kick_count[serial] = self.kick_count.get(serial, 0) + 1
                state = devices.get(serial)
                if ok:
                    log(f"🔄 {serial} ({state}) 已重新連線，等待手機跳出授權視窗 (第 {self.kick_count[serial]} 次)")
                else:
                    failed.append(serial)
                    log(f"❌ {serial} 重新連線失敗: {out}")

        if failed and now - self.last_global >= self.cooldown:
            self.last_global = now
            code, out = run(["adb", "reconnect", "offline"])
            log(f"🔄 改用 adb reconnect offline (重置所有未授權/離線裝置): {out or code}")


def main():
    ap = argparse.ArgumentParser(description="自動重連 unauthorized / offline 的 adb 裝置")
    ap.add_argument("--interval", type=float, default=5, help="檢查間隔秒數 (預設 5)")
    ap.add_argument("--grace", type=float, default=15,
                    help="裝置變成未授權後，等幾秒才重連 (預設 15，讓視窗與 App 有時間處理)")
    ap.add_argument("--cooldown", type=float, default=30, help="同一台裝置兩次重連的最短間隔 (預設 30)")
    ap.add_argument("--workers", type=int, default=16, help="並行重連數 (預設 16)")
    ap.add_argument("--once", action="store_true", help="只檢查一次 (忽略 grace / cooldown)")
    args = ap.parse_args()

    run(["adb", "start-server"], timeout=30)

    if args.once:
        wd = Watchdog(grace=0, cooldown=0, workers=args.workers)
        wd.tick()
        return

    wd = Watchdog(args.grace, args.cooldown, args.workers)
    log(f"ADB 授權看門狗啟動 (interval={args.interval}s grace={args.grace}s cooldown={args.cooldown}s)")
    while True:
        try:
            wd.tick()
        except Exception as e:  # 不要因為單次錯誤整個停掉
            log(f"錯誤: {e}")
        time.sleep(args.interval)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
