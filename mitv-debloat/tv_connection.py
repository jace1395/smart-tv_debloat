import os
import sys
import re
import socket
import subprocess

def resolve_tv_device(user_target=None):
    """
    Dynamically discovers the TV target device without any hardcoded IP.
    Priority:
      1. Explicit argument passed to the script (sys.argv[1] or user_target)
      2. Environment variables: TV_HOST, ADB_DEVICE, or ANDROID_SERIAL
      3. Already connected device in `adb devices`
      4. Auto-discovery: Probes LAN IPs in `arp -a` for open ADB port 5555
    """
    # 1. CLI argument or function parameter
    candidate = user_target
    if not candidate:
        # Check command line args (ignore flags like -h, --help)
        for arg in sys.argv[1:]:
            if not arg.startswith("-"):
                candidate = arg
                break

    # 2. Environment variables
    if not candidate:
        candidate = (
            os.environ.get("TV_HOST")
            or os.environ.get("ADB_DEVICE")
            or os.environ.get("ANDROID_SERIAL")
        )

    # If candidate found from CLI or ENV, format with port 5555 if it's an IP
    if candidate:
        candidate = candidate.strip()
        if re.match(r"^\d+\.\d+\.\d+\.\d+$", candidate):
            candidate = f"{candidate}:5555"
        # Connect to network target
        if ":" in candidate or re.match(r"^\d+\.\d+\.\d+\.\d+", candidate):
            subprocess.run(["adb", "connect", candidate], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        return candidate

    # 3. Check already connected devices in `adb devices`
    try:
        res = subprocess.run(["adb", "devices"], capture_output=True, text=True, errors="ignore")
        lines = [line.strip() for line in res.stdout.splitlines() if line.strip()]
        connected = []
        for line in lines[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                connected.append(parts[0])
        if len(connected) == 1:
            return connected[0]
        elif len(connected) > 1:
            # Prefer network device or MiTV if multiple
            return connected[0]
    except Exception:
        pass

    # 4. Auto-discover from local ARP table by testing port 5555
    try:
        arp_res = subprocess.run(["arp", "-a"], capture_output=True, text=True, errors="ignore")
        ips = re.findall(r"(\d+\.\d+\.\d+\.\d+)", arp_res.stdout)
        # Filter out obvious broadcast / gateway / multicast addresses
        candidates = []
        for ip in ips:
            if ip.endswith(".255") or ip.endswith(".1") or ip.startswith("224.") or ip.startswith("239.") or ip.startswith("255.") or ip.startswith("127."):
                continue
            if ip not in candidates:
                candidates.append(ip)

        for ip in candidates:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(0.3)
            try:
                s.connect((ip, 5555))
                s.close()
                target = f"{ip}:5555"
                subprocess.run(["adb", "connect", target], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                return target
            except Exception:
                pass
    except Exception:
        pass

    # If none found, inform the user clearly
    sys.exit(
        "\n[!] TV device could not be automatically detected.\n"
        "Since the TV uses a dynamic IP, please provide its current IP in one of these ways:\n"
        "  1. Run with IP argument:    python script.py <TV_IP>\n"
        "  2. Set environment variable: $env:TV_HOST = '<TV_IP>'\n"
        "  3. Connect once via ADB:    adb connect <TV_IP>:5555\n"
    )

def run_adb(args, target=None):
    """
    Executes an adb command targeting the dynamically resolved TV device.
    """
    device = resolve_tv_device(target)
    cmd = ["adb", "-s", device] + args
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors="ignore")
    return res.stdout
