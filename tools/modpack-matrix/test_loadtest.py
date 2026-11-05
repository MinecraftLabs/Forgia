import json
from pathlib import Path
import socket
import struct
import tempfile
import threading
import unittest
from unittest.mock import patch

from rcon import Rcon, RconError, packet, recv
from loadtest import COUNTER_KEYS, parse_diagnostics, patch_config, read_list


class RconTests(unittest.TestCase):
    def test_fragmented_frames_and_exact_4096_response(self):
        for size in (1, 4096, 10000):
            with self.subTest(size=size):
                client, server = socket.socketpair()
                client.settimeout(2)
                server.settimeout(2)
                expected = 'a' * size
                errors = []
                def serve():
                    try:
                        rid, kind, _ = recv(server)
                        self.assertEqual(kind, 3)
                        response = packet(rid, 2, '')
                        for byte in response:
                            server.sendall(bytes([byte]))
                        rid, kind, _ = recv(server)
                        self.assertEqual(kind, 2)
                        for start in range(0, size, 4096):
                            response = packet(rid, 0, expected[start:start+4096])
                            for offset in range(0, len(response), 7):
                                server.sendall(response[offset:offset+7])
                        marker, kind, _ = recv(server)
                        self.assertEqual(kind, 0)
                        server.sendall(packet(marker, 0, 'Unknown request 0'))
                    except BaseException as exc:
                        errors.append(exc)
                    finally:
                        server.close()
                thread = threading.Thread(target=serve, daemon=True)
                thread.start()
                with patch('socket.create_connection', return_value=client):
                    with Rcon('localhost', 1, 'test', timeout=2) as rcon:
                        self.assertEqual(expected, rcon.command('diagnostics'))
                thread.join(3)
                self.assertFalse(thread.is_alive())
                self.assertEqual([], errors)

    def test_closed_partial_frame_is_failure(self):
        client, server = socket.socketpair()
        server.sendall(struct.pack('<i', 30) + b'ab')
        server.close()
        with client, self.assertRaises(RconError):
            recv(client)


class LoadTests(unittest.TestCase):
    def test_missing_jar_is_not_a_successful_empty_test(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            names = base / 'list.txt'
            names.write_text('absent.jar\n')
            with self.assertRaises(FileNotFoundError):
                read_list(base, names)

    def test_settings_are_root_keys_not_last_table_entries(self):
        import tomllib
        with tempfile.TemporaryDirectory() as tmp:
            config = Path(tmp) / 'config.toml'
            config.write_text('enabled = false\n[other]\nkeep = 5\n')
            patch_config(config, {'enabled':'true', 'newFlag':'false'})
            parsed = tomllib.loads(config.read_text())
            self.assertEqual({'enabled':True, 'newFlag':False, 'other':{'keep':5}}, parsed)

    def test_parses_all_failure_slices_and_rejects_truncated_status(self):
        raw = ('Region threading diagnostics: enabled=true, '
               + ', '.join(k + '=0' for k in COUNTER_KEYS)
               + ', tickFailures=entity=2, block_entity=3, chunk=4, spawn=5, scheduled=6'
               + ', deferredUpdateBudgetChunkTicks=0, entityStorageMode=REGION_OWNED'
               + ', borderUpdates=recorded=8/applied=7/dropped=1'
               + ', gameEvents=posted=2/deferred=1/delivered=1/refused=0/dropped=0'
               + ' Region players: entries=1')
        parsed = parse_diagnostics(raw)
        self.assertEqual(20, parsed['tickFailures'])
        self.assertEqual(1, parsed['borderUpdates.dropped'])
        with self.assertRaises(ValueError):
            parse_diagnostics(raw[:100])


if __name__ == '__main__':
    unittest.main()
