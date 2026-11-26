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
    # Output format: Physical size: 1080x2400 or Override size: ...
    match = re.search(r'(\d+)x(\d+)', out)
    if match:
        return int(match.group(1)), int(match.group(2))
    return 1080, 2012 # default fallback

def shutdown_device(serial):
    print(f"[{serial}] 🚀 Starting clean UI shutdown...")

    # 1. Wake & unlock
    run_cmd(f"adb -s {serial} shell input keyevent KEYCODE_WAKEUP")
    run_cmd(f"adb -s {serial} shell wm dismiss-keyguard")
    run_cmd(f"adb -s {serial} shell input keyevent 82")
    time.sleep(0.3)

    width, height = get_screen_size(serial)
    center_x = width // 2
    # First Power off button is around 33% - 35% height
    pwr_y1 = int(height * 0.336)
    # Confirmation Power off button is around center 50% height
    pwr_y2 = int(height * 0.503)

    # 2. Trigger Power Menu
    run_cmd(f"adb -s {serial} shell input keyevent --longpress 26")
    time.sleep(0.8)

    # 3. Tap Power Off
    run_cmd(f"adb -s {serial} shell input tap {center_x} {pwr_y1}")
    time.sleep(0.6)

    # 4. Tap Confirm Power Off (second tap)
    run_cmd(f"adb -s {serial} shell input tap {center_x} {pwr_y2}")
    
    # Also fallback tap slightly lower or center in case of slightly different UI scale
    time.sleep(0.3)
    run_cmd(f"adb -s {serial} shell input tap {center_x} {height // 2}")

    print(f"[{serial}] ✅ Clean UI Power Off dispatched.")

def main():
    devices = get_devices()
    authorized = [d[0] for d in devices if d[1] == "device"]
    unauthorized = [d[0] for d in devices if d[1] == "unauthorized"]

    print(f"Found {len(authorized)} authorized devices to shut down: {authorized}")

    with ThreadPoolExecutor(max_workers=16) as executor:
        list(executor.map(shutdown_device, authorized))

    print("\n⏳ Waiting 5 seconds for devices to power down...")
    time.sleep(5)

    rem_devices = get_devices()
    rem_authorized = [d[0] for d in rem_devices if d[1] == "device"]
    print(f"\nRemaining connected authorized devices: {len(rem_authorized)}")
    if rem_authorized:
        print(f"Devices still online: {rem_authorized}")
    else:
        print("🎉 ALL authorized devices successfully powered off and shut down!")

if __name__ == "__main__":
    main()
