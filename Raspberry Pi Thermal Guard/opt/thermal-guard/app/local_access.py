"""Allow certain endpoints only from the Pi itself (the kiosk browser).

The backend listens on 0.0.0.0 so phones on the LAN / Tailscale can read
/api/live, but anything that changes the device (siren, Wi-Fi) must come from
the kiosk touchscreen, which loads http://127.0.0.1:8080/kiosk.
"""
import ipaddress

from fastapi import HTTPException, Request


def is_local_client(request: Request) -> bool:
    host = request.client.host if request.client else ''
    if host == 'localhost':
        return True
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return False
    # Also accept IPv4-mapped IPv6 (::ffff:127.0.0.1).
    mapped = getattr(ip, 'ipv4_mapped', None)
    return ip.is_loopback or bool(mapped and mapped.is_loopback)


def require_local_client(request: Request) -> None:
    """FastAPI dependency: 403 unless the request comes from the Pi itself."""
    if not is_local_client(request):
        raise HTTPException(
            status_code=403,
            detail='This action is only allowed from the Thermal Guard kiosk screen.',
        )
