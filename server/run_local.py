from __future__ import annotations

import os
import socket

import uvicorn


def _local_ip() -> str:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return str(sock.getsockname()[0])
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


def main() -> None:
    os.environ.setdefault("CLOUD_RUNTIME_ENV", "local")
    os.environ.setdefault("CLOUD_API_KEY", "local-token")
    os.environ.setdefault("CLOUD_WORKERS", "1")
    host = os.getenv("CLOUD_HOST", "0.0.0.0")
    port = int(os.getenv("CLOUD_PORT", "8000"))
    local_ip = _local_ip()
    print(f"Connection QR: http://{local_ip}:{port}/debug/connect?api_key=local-token")
    uvicorn.run("server.app:app", host=host, port=port, workers=1)


if __name__ == "__main__":
    main()
