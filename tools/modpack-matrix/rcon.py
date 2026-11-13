#!/usr/bin/env python3
"""Minecraft RCON client with exact framing and an explicit end-of-response marker."""
import argparse
import os
import socket
import struct


class RconError(RuntimeError):
    pass


def packet(request_id, kind, body):
    payload = struct.pack('<ii', request_id, kind) + body.encode('utf-8') + b'\0\0'
    return struct.pack('<i', len(payload)) + payload


def read_exact(sock, size):
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise RconError('Connection closed before the complete RCON frame arrived')
        data.extend(chunk)
    return bytes(data)


def recv(sock):
    size, = struct.unpack('<i', read_exact(sock, 4))
    if not 10 <= size <= 4 * 1024 * 1024:
        raise RconError(f'Invalid RCON frame size: {size}')
    data = read_exact(sock, size)
    if data[-2:] != b'\0\0':
        raise RconError('Invalid RCON frame terminator')
    request_id, kind = struct.unpack('<ii', data[:8])
    return request_id, kind, data[8:-2]


class Rcon:
    def __init__(self, host, port, password, timeout=60):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        self.next_id = 10
        try:
            self.sock.sendall(packet(1, 3, password))
            request_id, kind, _ = recv(self.sock)
            if request_id != 1 or kind != 2:
                raise RconError('RCON authentication failed')
        except BaseException:
            self.close()
            raise

    def close(self):
        self.sock.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def command(self, command):
        request_id = self.next_id
        self.next_id += 2
        marker = request_id + 1
        if len(command.encode('utf-8')) > 1400:
            raise RconError('Command exceeds Minecraft RCON input buffer')
        self.sock.sendall(packet(request_id, 2, command))
        # Wait for the first frame before sending the sentinel: Minecraft reads one input frame at a time.
        # Type 0 produces an "Unknown request" reply, with no game command or world mutation.
        first_id, kind, data = recv(self.sock)
        if first_id != request_id or kind != 0:
            raise RconError('Unexpected command reply id/type')
        chunks = [data]
        self.sock.sendall(packet(marker, 0, ''))
        while True:
            reply_id, kind, data = recv(self.sock)
            if reply_id == marker:
                if kind != 0:
                    raise RconError('Unexpected end-marker reply type')
                break
            if reply_id != request_id or kind != 0:
                raise RconError('Unexpected multipart reply id/type')
            chunks.append(data)
            if sum(map(len, chunks)) > 16 * 1024 * 1024:
                raise RconError('RCON response exceeds capture limit')
        return b''.join(chunks).decode('utf-8', 'replace')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('host')
    parser.add_argument('port', type=int)
    parser.add_argument('password', help='Use - to read RCON_PASSWORD from the environment')
    parser.add_argument('commands', nargs='+')
    parser.add_argument('--timeout', type=float, default=60)
    args = parser.parse_args()
    password = os.environ['RCON_PASSWORD'] if args.password == '-' else args.password
    try:
        with Rcon(args.host, args.port, password, args.timeout) as client:
            for command in args.commands:
                print(client.command(command))
    except (OSError, RconError) as exc:
        parser.exit(1, f'RCON failed: {exc}\n')


if __name__ == '__main__':
    main()
