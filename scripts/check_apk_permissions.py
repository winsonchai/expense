#!/usr/bin/env python3
"""Fail if the packaged app acquires permissions outside its intended local workflow."""
import re
import subprocess
import sys

if len(sys.argv) != 3:
    raise SystemExit("Usage: check_apk_permissions.py /path/to/aapt /path/to/app.apk")
output = subprocess.check_output([sys.argv[1], "dump", "permissions", sys.argv[2]], text=True)
permissions = set(re.findall(r"uses-permission(?:-sdk-\d+)?: name='([^']+)'", output))
forbidden = {
    "android.permission.INTERNET", "android.permission.READ_SMS",
    "android.permission.RECEIVE_SMS", "android.permission.RECORD_AUDIO",
    "android.permission.CAMERA", "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_IMAGES", "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
}
unexpected = permissions & forbidden
if unexpected:
    raise SystemExit("Unexpected packaged permissions: " + ", ".join(sorted(unexpected)))
print("Packaged permission check passed. Declared permissions: " + ", ".join(sorted(permissions)))
