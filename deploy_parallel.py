import subprocess
import time
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

APK_PATH = "/Users/scsonic/Documents/AutoAcceptADB/app/build/outputs/apk/debug/app-debug.apk"
PKG_NAME = "com.autoaccept.adb"
SVC_NAME = "com.autoaccept.adb/com.autoaccept.adb.AdbAccessibilityService"

def log(msg):
    print(msg, flush=True)

def run_cmd(cmd, timeout=30):
    try:
        res = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return res.returncode, res.stdout.strip(), res.stderr.strip()
    except subprocess.TimeoutExpired:
        return -1, "", "Command timed out"

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

def setup_device(serial):
    log(f"[{serial}] 🚀 Starting setup...")

    # 1. Wake screen & unlock
    run_cmd(f"adb -s {serial} shell input keyevent KEYCODE_WAKEUP")
    run_cmd(f"adb -s {serial} shell wm dismiss-keyguard")

    # 2. Install APK
    log(f"[{serial}] 📦 Installing APK...")
    code, out, err = run_cmd(f"adb -s {serial} install -r -d -g {APK_PATH}", timeout=45)
    install_ok = "Success" in out

    # 3. Notification permission
    run_cmd(f"adb -s {serial} shell pm grant {PKG_NAME} android.permission.POST_NOTIFICATIONS 2>/dev/null")

    # 4. Battery Optimization whitelist (Doze whitelist)
    run_cmd(f"adb -s {serial} shell dumpsys deviceidle whitelist +{PKG_NAME}")

    # 5. AppOps background & overlay
    run_cmd(f"adb -s {serial} shell appops set {PKG_NAME} SYSTEM_ALERT_WINDOW allow 2>/dev/null")
    run_cmd(f"adb -s {serial} shell appops set {PKG_NAME} RUN_IN_BACKGROUND allow 2>/dev/null")
    run_cmd(f"adb -s {serial} shell appops set {PKG_NAME} RUN_ANY_IN_BACKGROUND allow 2>/dev/null")
    run_cmd(f"adb -s {serial} shell appops set {PKG_NAME} AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>/dev/null")

    # 6. Enable Accessibility Service via secure settings
    code, current_svc, _ = run_cmd(f"adb -s {serial} shell settings get secure enabled_accessibility_services")
    if SVC_NAME not in current_svc:
        if not current_svc or current_svc == "null":
            new_svc = SVC_NAME
        else:
            new_svc = f"{current_svc}:{SVC_NAME}"
        run_cmd(f"adb -s {serial} shell settings put secure enabled_accessibility_services {new_svc}")
    run_cmd(f"adb -s {serial} shell settings put secure accessibility_enabled 1")

    # 7. Start MainActivity
    run_cmd(f"adb -s {serial} shell am start -n {PKG_NAME}/.MainActivity")
    time.sleep(1.5)

    # 8. Verify
    _, active_svcs, _ = run_cmd(f"adb -s {serial} shell settings get secure enabled_accessibility_services")
    _, is_acc_enabled, _ = run_cmd(f"adb -s {serial} shell settings get secure accessibility_enabled")
    _, idle_dump, _ = run_cmd(f"adb -s {serial} shell dumpsys deviceidle whitelist")
    _, running_svcs, _ = run_cmd(f"adb -s {serial} shell dumpsys activity services {PKG_NAME}")

    acc_setting_ok = SVC_NAME in active_svcs and is_acc_enabled == "1"
    battery_ok = PKG_NAME in idle_dump
    keepalive_running = "KeepAliveService" in running_svcs
    acc_service_running = "AdbAccessibilityService" in running_svcs

    log(f"[{serial}] ✨ Finished. Install: {'OK' if install_ok else 'FAIL'}, AccService: {'RUNNING' if acc_service_running else 'WAIT'}, Battery: {'OK' if battery_ok else 'NO'}, KeepAlive: {'OK' if keepalive_running else 'NO'}")

    return {
        "serial": serial,
        "install_ok": install_ok,
        "accessibility_setting": acc_setting_ok,
        "accessibility_service": acc_service_running,
        "battery_whitelist": battery_ok,
        "keepalive_service": keepalive_running
    }

def main():
    devices = get_devices()
    log(f"Found {len(devices)} total connected devices:")
    authorized = [d[0] for d in devices if d[1] == "device"]
    unauthorized = [d[0] for d in devices if d[1] == "unauthorized"]

    log(f"Authorized devices ({len(authorized)}): {authorized}")
    if unauthorized:
        log(f"Unauthorized devices ({len(unauthorized)}): {unauthorized}")

    log(f"\n🚀 Launching parallel deployment across {len(authorized)} devices...")
    start_time = time.time()

    results = []
    with ThreadPoolExecutor(max_workers=16) as executor:
        futures = {executor.submit(setup_device, serial): serial for serial in authorized}
        for future in as_completed(futures):
            res = future.result()
            results.append(res)

    results.sort(key=lambda x: x["serial"])
    elapsed = time.time() - start_time
    log(f"\n⏱ Completed parallel deployment in {elapsed:.1f} seconds.")

    log("\n" + "="*80)
    log("🎯 FINAL DEPLOYMENT & PERMISSION SUMMARY TABLE")
    log("="*80)
    log(f"{'Serial':<16} | {'Install':<10} | {'Accessibility':<15} | {'Acc Service':<13} | {'Battery':<10} | {'KeepAlive':<10}")
    log("-" * 80)
    for r in results:
        ins = "✅ OK" if r["install_ok"] else "❌ Failed"
        acc_s = "✅ Enabled" if r["accessibility_setting"] else "❌ Failed"
        acc_srv = "✅ Running" if r["accessibility_service"] else "⚠️ Pending"
        bat_s = "✅ Whitelisted" if r["battery_whitelist"] else "❌ Not added"
        keep_s = "✅ Active" if r["keepalive_service"] else "❌ Inactive"
        log(f"{r['serial']:<16} | {ins:<10} | {acc_s:<15} | {acc_srv:<13} | {bat_s:<10} | {keep_s:<10}")

    if unauthorized:
        log("\n⚠️ Note on 'unauthorized' devices:")
        for u in unauthorized:
            log(f"   - {u}: ADB RSA authorization dialog is not accepted yet on phone screen.")

if __name__ == "__main__":
    main()
