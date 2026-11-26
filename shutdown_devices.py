import subprocess
import time
import re
from concurrent.futures import ThreadPoolExecutor

def run_cmd(cmd, timeout=10):
    try:
        res = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return res.returncode, res.stdout.strip(), res.stderr.strip()
    except subprocess.TimeoutExpired:
        return -1, "", "Timeout"

def get_devices():
    code, stdout, stderr = run_cmd("adb devices")
    devices = []
    lines = stdout.splitlines()
    for line in lines[1:]:
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) >= 2:
            devices.append((parts[0], parts[1]))
    return devices

def get_screen_size(serial):
    code, out, _ = run_cmd(f"adb -s {serial} shell wm size")
    match = re.search(r'(\d+)x(\d+)', out)
    if match:
        return int(match.group(1)), int(match.group(2))
    return 1080, 2012

def shutdown_device(serial):
    print(f"[{serial}] 🔌 正在喚醒螢幕並長按電源鍵觸發系統關機流程...")
    
    # 1. 喚醒與向上滑動解鎖
    run_cmd(f"adb -s {serial} shell input keyevent KEYCODE_WAKEUP")
    run_cmd(f"adb -s {serial} shell wm dismiss-keyguard")
    run_cmd(f"adb -s {serial} shell input swipe 540 1500 540 500")
    run_cmd(f"adb -s {serial} shell input keyevent 82")
    time.sleep(0.3)

    width, height = get_screen_size(serial)
    center_x = width // 2
    pwr_y1 = int(height * 0.336)
    pwr_y2 = int(height * 0.503)

    # 2. 長按電源鍵彈出原生選單
    run_cmd(f"adb -s {serial} shell input keyevent --longpress 26")
    time.sleep(0.8)

    # 3. 點擊「關機」圖示
    run_cmd(f"adb -s {serial} shell input tap {center_x} {pwr_y1}")
    time.sleep(0.6)

    # 4. 點擊「再次輕觸以關閉手機」確認圖示
    run_cmd(f"adb -s {serial} shell input tap {center_x} {pwr_y2}")
    time.sleep(0.3)
    run_cmd(f"adb -s {serial} shell input tap {center_x} {height // 2}")

    print(f"[{serial}] ✅ 已送出關機確認點擊。")

def main():
    devices = get_devices()
    authorized = [d[0] for d in devices if d[1] == "device"]
    unauthorized = [d[0] for d in devices if d[1] == "unauthorized"]

    print(f"==================================================")
    print(f"📱 正在為 {len(authorized)} 台已授權 Android 手機執行關機...")
    print(f"==================================================")

    with ThreadPoolExecutor(max_workers=16) as executor:
        list(executor.map(shutdown_device, authorized))

    print("\n" + "="*50)
    print("🎯 全部設備已成功送出關機指令與點擊確認！")
    print("⚠️ 提示：若手機接在帶電的 USB Hub 上，部分三星機型會因持續供電而進入充電開機，拔除 USB 線即可徹底保持關機。")
    print("="*50)

if __name__ == "__main__":
    main()
