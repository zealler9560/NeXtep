#!/usr/bin/env python3
"""Read-only readiness probe. Never dismisses the keyguard or wakes the device."""
import argparse
import json
import re
import subprocess
import time


def readiness(policy):
    section = policy.split("KeyguardServiceDelegate", 1)[-1] if "KeyguardServiceDelegate" in policy else ""
    fields = dict(re.findall(r"^\s+(showing|inputRestricted|bootCompleted|screenState|interactiveState)=(\w+)\s*$", section, re.M))
    expected = {"showing": "false", "inputRestricted": "false", "bootCompleted": "true",
                "screenState": "SCREEN_STATE_ON", "interactiveState": "INTERACTIVE_STATE_AWAKE"}
    return all(fields.get(key) == value for key, value in expected.items()), fields


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--wait", type=float, default=0, help="maximum seconds to wait; default is one read")
    args = parser.parse_args()
    deadline = time.monotonic() + max(0, args.wait)
    while True:
        try:
            result = subprocess.run([args.adb, "-s", args.serial, "shell", "dumpsys", "window", "policy"],
                                    capture_output=True, text=True, timeout=10, check=True)
            ready, fields = readiness(result.stdout)
            state = {"ready": ready, "keyguard": fields}
        except (subprocess.SubprocessError, OSError) as error:
            ready = False
            state = {"ready": False, "error": type(error).__name__}
        if ready or time.monotonic() >= deadline:
            print(json.dumps(state, ensure_ascii=False))
            return 0 if ready else 1
        time.sleep(min(3, max(0, deadline - time.monotonic())))


if __name__ == "__main__":
    raise SystemExit(main())
