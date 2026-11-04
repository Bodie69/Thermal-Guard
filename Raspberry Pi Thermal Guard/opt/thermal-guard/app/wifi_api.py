from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from typing import Optional
import subprocess
import time

from .local_access import require_local_client

router = APIRouter()

# Every endpoint that changes Wi-Fi (or turns the radio on to scan) is
# kiosk-only. Without this anyone on the LAN/Tailscale could switch Wi-Fi off
# and cut the Pi off from Firebase and the app. Status stays readable.
LOCAL_ONLY = Depends(require_local_client)


class WifiConnectRequest(BaseModel):
    ssid: str
    password: str = ""


class WifiPowerRequest(BaseModel):
    enabled: Optional[bool] = None


def run(args, timeout=20, fail=True, input=None):
    try:
        p = subprocess.run(
            args,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
            # Always give the child a stdin so it never waits on a terminal.
            input=input if input is not None else ""
        )
    except subprocess.TimeoutExpired:
        raise HTTPException(504, "Wi-Fi command timeout")
    except FileNotFoundError:
        raise HTTPException(503, f"Command not found: {args[0]}")

    if fail and p.returncode != 0:
        raise HTTPException(
            500,
            (p.stderr or p.stdout or "Command failed").strip()
        )

    return p.stdout.strip()


def privileged(*args, timeout=30, input=None):
    return run(
        ["/usr/bin/sudo", "-n", "/usr/local/sbin/thermal-guard-wifi", *args],
        timeout=timeout,
        input=input
    )


def radio_enabled():
    return run(
        ["/usr/bin/nmcli", "radio", "wifi"]
    ).strip().lower() == "enabled"


def get_status():
    radio = radio_enabled()

    output = run([
        "/usr/bin/nmcli",
        "-t",
        "-f",
        "DEVICE,TYPE,STATE,CONNECTION",
        "device",
        "status"
    ])

    interface = "wlan0"
    state = "UNAVAILABLE"
    ssid = ""

    for line in output.splitlines():
        fields = line.split(":", 3)

        if len(fields) >= 4 and fields[1] == "wifi":
            interface = fields[0]
            state = fields[2].upper()
            ssid = "" if fields[3] == "--" else fields[3]
            break

    return {
        "ok": True,
        "available": True,
        "radio_on": radio,
        "wifi_on": radio,
        "enabled": radio,
        "interface": interface,
        "state": state,
        "connected": state.lower() == "connected",
        "ssid": ssid
    }


# STATUS — aliases for old/new kiosk code
@router.get("/api/wifi/status")
@router.get("/wifi/status")
@router.get("/api/network/wifi/status")
def wifi_status():
    return get_status()


# ON
@router.post("/api/wifi/on")
@router.post("/api/wifi/enable")
@router.post("/wifi/on")
@router.post("/api/network/wifi/on")
@router.post("/api/network/wifi/enable")
def wifi_on(_local: None = LOCAL_ONLY):
    privileged("on")
    time.sleep(1)

    return {
        "message": "Wi-Fi enabled",
        **get_status()
    }


# OFF
@router.post("/api/wifi/off")
@router.post("/api/wifi/disable")
@router.post("/wifi/off")
@router.post("/api/network/wifi/off")
@router.post("/api/network/wifi/disable")
def wifi_off(_local: None = LOCAL_ONLY):
    privileged("off")
    time.sleep(0.5)

    return {
        "message": "Wi-Fi disabled",
        **get_status()
    }


# TOGGLE — compatibility endpoint
@router.post("/api/wifi/toggle")
@router.post("/wifi/toggle")
@router.post("/api/network/wifi/toggle")
def wifi_toggle(data: Optional[WifiPowerRequest] = None, _local: None = LOCAL_ONLY):
    current = radio_enabled()

    if data is not None and data.enabled is not None:
        target = data.enabled
    else:
        target = not current

    if target:
        privileged("on")
    else:
        privileged("off")

    time.sleep(1)

    return get_status()


# SCAN
@router.get("/api/wifi/scan")
@router.get("/api/wifi/networks")
@router.get("/wifi/scan")
@router.get("/api/network/wifi/scan")
@router.get("/api/network/wifi/networks")
def wifi_scan(_local: None = LOCAL_ONLY):
    if not radio_enabled():
        privileged("on")
        time.sleep(1)

    # Rescan; don't fail entire request if rescan itself is busy.
    run([
        "/usr/bin/nmcli",
        "device",
        "wifi",
        "rescan",
        "ifname",
        "wlan0"
    ], timeout=15, fail=False)

    time.sleep(0.5)

    output = run([
        "/usr/bin/nmcli",
        "-t",
        "-f",
        "IN-USE,SSID,SIGNAL,SECURITY",
        "device",
        "wifi",
        "list",
        "ifname",
        "wlan0"
    ])

    networks = []
    seen = {}

    for line in output.splitlines():
        parts = line.split(":", 3)

        if len(parts) < 4:
            continue

        in_use, ssid, signal, security = parts
        ssid = ssid.strip()

        if not ssid:
            continue

        try:
            signal = int(signal)
        except Exception:
            signal = 0

        item = {
            "ssid": ssid,
            "signal": signal,
            "security": security or "OPEN",
            "connected": in_use.strip() == "*"
        }

        previous = seen.get(ssid)

        if previous is None or signal > previous["signal"]:
            seen[ssid] = item

    networks = sorted(
        seen.values(),
        key=lambda x: (not x["connected"], -x["signal"])
    )

    return {
        "ok": True,
        "radio_on": True,
        "wifi_on": True,
        "networks": networks
    }


# CONNECT
@router.post("/api/wifi/connect")
@router.post("/wifi/connect")
@router.post("/api/network/wifi/connect")
def wifi_connect(data: WifiConnectRequest, _local: None = LOCAL_ONLY):
    ssid = data.ssid.strip()

    if not ssid:
        raise HTTPException(400, "SSID required")

    # The password goes to the root helper on stdin, never on the command
    # line, so it cannot be read from `ps` or /proc/<pid>/cmdline.
    result = privileged(
        "connect",
        ssid,
        timeout=60,
        input=data.password + "\n"
    )

    time.sleep(1)

    return {
        "ok": True,
        "message": result or f"Connected to {ssid}",
        **get_status()
    }
