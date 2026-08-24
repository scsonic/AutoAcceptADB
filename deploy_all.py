import subprocess
import time
import sys
import os

APK_PATH = "/Users/scsonic/Documents/AutoAcceptADB/app/build/outputs/apk/debug/app-debug.apk"
PKG_NAME = "com.autoaccept.adb"
SVC_NAME = "com.autoaccept.adb/com.autoaccept.adb.AdbAccessibilityService"

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
    print(f"\n==================================================")
    print(f"🚀 Processing Device: {serial}")
    print(f"==================================================")

    # 1. Wake up screen & dismiss keyguard
    print(f"[{serial}] 1. Waking up screen...")
    run_cmd(f"adb -s {serial} shell input keyevent KEYCODE_WAKEUP")
    run_cmd(f"adb -s {serial} shell wm dismiss-keyguard")

    # 2. Install APK
    print(f"[{serial}] 2. Installing APK ({APK_PATH})...")
    code, out, err = run_cmd(f"adb -s {serial} install -r -d -g {APK_PATH}", timeout=60)
    if "Success" in out:
        print(f"[{serial}]    ✅ Installed successfully.")
    else:
        print(f"[{serial}]    ⚠️ Install output: {out} {err}")

    # 3. Grant notification permission (Android 13+)
    print(f"[{serial}] 3. Granting notification permission...")
    run_cmd(f"adb -s {serial} shell pm grant {PKG_NAME} android.permission.POST_NOTIFICATIONS")

    # 4. Battery Optimization Whitelist
    print(f"[{serial}] 4. Whitelisting battery optimization (Doze)...")
    code, out, err = run_cmd(f"adb -s {serial} shell dumpsys deviceidle whitelist +{PKG_NAME}")
    print(f"[{serial}]    Battery Whitelist: {out}")

    # 5. AppOps permissions (Background running, overlay, keepalive, no auto-revoke)
    print(f"[{serial}] 5. Setting AppOps background & overlay permissions...")
    run_cmd(f"adb -s {serial} shell appops set {PKG_NAME} SYSTEM_ALERT_WINDOW allow")
    run_cmd(f"adb -s {serial} shell appops set {PKG_NAME} RUN_IN_BACKGROUND allow")
    run_cmd(f"adb -s {serial} shell appops set {PKG_NAME} RUN_ANY_IN_BACKGROUND allow")
    run_cmd(f"adb -s {serial} shell appops set {PKG_NAME} AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore")

    # 6. Enable Accessibility Service via Secure Settings
    print(f"[{serial}] 6. Enabling Accessibility Service...")
    code, current_svc, _ = run_cmd(f"adb -s {serial} shell settings get secure enabled_accessibility_services")
    if SVC_NAME not in current_svc:
        if not current_svc or current_svc == "null":
            new_svc = SVC_NAME
        else:
            new_svc = f"{current_svc}:{SVC_NAME}"
        run_cmd(f"adb -s {serial} shell settings put secure enabled_accessibility_services {new_svc}")
    run_cmd(f"adb -s {serial} shell settings put secure accessibility_enabled 1")

    # 7. Start MainActivity & Services
    print(f"[{serial}] 7. Starting MainActivity & KeepAlive Service...")
    run_cmd(f"adb -s {serial} shell am start -n {PKG_NAME}/.MainActivity")
    time.sleep(1.5)

    # 8. Verify Status
    print(f"[{serial}] 8. Verifying permissions and active services...")
    _, active_svcs, _ = run_cmd(f"adb -s {serial} shell settings get secure enabled_accessibility_services")
    _, is_acc_enabled, _ = run_cmd(f"adb -s {serial} shell settings get secure accessibility_enabled")
    _, idle_dump, _ = run_cmd(f"adb -s {serial} shell dumpsys deviceidle whitelist")
    _, running_svcs, _ = run_cmd(f"adb -s {serial} shell dumpsys activity services {PKG_NAME}")

    acc_ok = SVC_NAME in active_svcs and is_acc_enabled == "1"
    battery_ok = PKG_NAME in idle_dump
    keepalive_running = "KeepAliveService" in running_svcs
    acc_service_running = "AdbAccessibilityService" in running_svcs

    print(f"[{serial}] Status Summary:")
    print(f"   - Accessibility Setting: {'✅ ENABLED' if acc_ok else '❌ DISABLED'}")
    print(f"   - Accessibility Service: {'✅ RUNNING' if acc_service_running else '❌ STOPPED'}")
    print(f"   - Battery Whitelist:     {'✅ WHITELISTED' if battery_ok else '❌ OPTIMIZED'}")
    print(f"   - KeepAlive Foreground:  {'✅ ACTIVE' if keepalive_running else '❌ INACTIVE'}")

    return {
        "serial": serial,
        "accessibility_setting": acc_ok,
        "accessibility_service": acc_service_running,
        "battery_whitelist": battery_ok,
        "keepalive_service": keepalive_running
    }

def main():
    devices = get_devices()
    print(f"Found {len(devices)} connected devices in adb devices:")
    authorized = [d[0] for d in devices if d[1] == "device"]
    unauthorized = [d[0] for d in devices if d[1] == "unauthorized"]

    print(f"Authorized devices ({len(authorized)}): {authorized}")
    print(f"Unauthorized devices ({len(unauthorized)}): {unauthorized}")

    results = []
    for serial in authorized:
        res = setup_device(serial)
        results.append(res)

    print("\n" + "="*70)
    print("🎯 FINAL DEPLOYMENT & PERMISSION SUMMARY TABLE")
    print("="*70)
    print(f"{'Serial':<16} | {'Accessibility':<15} | {'Acc Service':<13} | {'Battery':<10} | {'KeepAlive':<10}")
    print("-" * 70)
    for r in results:
        acc_s = "✅ Enabled" if r["accessibility_setting"] else "❌ Failed"
        acc_srv = "✅ Running" if r["accessibility_service"] else "❌ Stopped"
        bat_s = "✅ Whitelisted" if r["battery_whitelist"] else "❌ Not added"
        keep_s = "✅ Active" if r["keepalive_service"] else "❌ Inactive"
        print(f"{r['serial']:<16} | {acc_s:<15} | {acc_srv:<13} | {bat_s:<10} | {keep_s:<10}")

    if unauthorized:
        print("\n⚠️ Note: The following devices are currently in 'unauthorized' ADB state:")
        for u in unauthorized:
            print(f"   - {u} (Please ensure screen is on and RSA authorization dialog is accepted)")

if __name__ == "__main__":
    main()
