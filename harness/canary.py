#!/usr/bin/env python3
"""Isolated canary listener — the network indicator.

Binds a local TCP port and records the fact that *something connected*. It never
serves content, never responds with anything executable, and never runs code on
behalf of a connection. Its only job is to let a detector answer: did the target
reach out? That is the benign side-effect used to tell whether a resolver-style
fix (e.g. JNDI) still holds.

Run in the isolated lab only:

    python3 harness/canary.py --host 127.0.0.1 --port 18444 --log lab/canary.log
"""
import argparse
import datetime
import socket
import threading


def _handle(conn, addr, logpath):
    # Record the connection, read-and-discard a little, then close. No reply.
    stamp = datetime.datetime.utcnow().isoformat() + "Z"
    line = f"{stamp} CONNECT from {addr[0]}:{addr[1]}\n"
    with open(logpath, "a", encoding="utf-8") as fh:
        fh.write(line)
    print(line, end="")
    try:
        conn.settimeout(1.0)
        conn.recv(256)  # drained and ignored; never interpreted
    except OSError:
        pass
    finally:
        conn.close()


def main():
    ap = argparse.ArgumentParser(description="Canary listener (network indicator)")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=18444)
    ap.add_argument("--log", default="lab/canary.log")
    args = ap.parse_args()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(16)
    print(f"canary listening on {args.host}:{args.port} -> {args.log}")
    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(
                target=_handle, args=(conn, addr, args.log), daemon=True
            ).start()
    except KeyboardInterrupt:
        print("\ncanary stopped")
    finally:
        srv.close()


if __name__ == "__main__":
    main()
