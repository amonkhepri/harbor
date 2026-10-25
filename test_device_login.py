import os
import shlex
import subprocess
import sys
import time

creds_path = os.path.expanduser("~/.local/state/harbor-matrix-disposable/creds.env")
env = {}
with open(creds_path, "r") as f:
    for line in f:
        line = line.strip()
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip()

account_a_user = env.get("ACCOUNT_A_USER", "harbor_test_a")
account_a_pass = env.get("ACCOUNT_A_PASS", "")

if not account_a_pass:
    print("Missing password", file=sys.stderr)
    sys.exit(1)

# Ensure adb reverse is active
subprocess.run(["adb", "reverse", "tcp:8008", "tcp:8008"], check=True)

# Focus password field
subprocess.run(["adb", "shell", "input", "tap", "200", "595"], check=True)
time.sleep(0.5)

# Select all and delete in password field
subprocess.run(["adb", "shell", "input", "keycombination", "113", "29"], check=True)  # Ctrl+A
subprocess.run(["adb", "shell", "input", "keyevent", "67"], check=True)  # DEL (clears selection)

# Fallback: ensure the entire password field is cleared even if selection failed or text is longer
subprocess.run(["adb", "shell", "input", "keyevent", "123"], check=False)  # KEYCODE_MOVE_END
del_keys = ["67"] * 100
subprocess.run(["adb", "shell", "input", "keyevent"] + del_keys, check=False)  # DEL
subprocess.run(["adb", "shell", "input", "keyevent", "122"], check=False)  # KEYCODE_MOVE_HOME
forward_del_keys = ["112"] * 100
subprocess.run(["adb", "shell", "input", "keyevent"] + forward_del_keys, check=False)  # FORWARD_DEL

# Using shlex.quote for safe shell escaping to the device sh
escaped_text = shlex.quote(account_a_pass.replace(" ", "%s"))
cmd = ["adb", "shell", f"input text {escaped_text}"]
try:
    res = subprocess.run(cmd, check=False)
    if res.returncode != 0:
        print(f"Failed to input password via adb (exit status {res.returncode})", file=sys.stderr)
        sys.exit(res.returncode)
except Exception as e:
    print(f"Failed to execute adb input: {type(e).__name__}", file=sys.stderr)
    sys.exit(1)
time.sleep(0.5)

# Tap Sign in ([540, 717])
subprocess.run(["adb", "shell", "input", "tap", "540", "717"], check=True)
print("Submitted credentials successfully")

