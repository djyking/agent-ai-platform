import json
import http.server
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from phase6_continuous_observation import Identity, atomic_json, continuity_failure, percentile, http as observation_http


class ContinuousObservationTest(unittest.TestCase):
    def test_loopback_requests_never_use_system_proxy_or_forward_local_tokens(self):
        received = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                received.append(self.headers.get("Authorization"))
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"ok":true}')

            def log_message(self, *args):
                pass

        with http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                # No system bypass exists in this scenario; an inherited proxy would intercept.
                with patch("urllib.request.getproxies", return_value={"http": "http://127.0.0.1:9"}) as proxy, \
                        patch("urllib.request.proxy_bypass", return_value=False):
                    status, result = observation_http(
                        "http://127.0.0.1:" + str(server.server_port), "GET", "/health",
                        {"Authorization": "Bearer synthetic-local-token"})
                self.assertEqual(200, status)
                self.assertTrue(result["ok"])
                self.assertEqual(["Bearer synthetic-local-token"], received)
                proxy.assert_not_called()
            finally:
                server.shutdown()
                thread.join(timeout=2)

    def test_suspend_and_wall_clock_change_cannot_count_as_continuous_time(self):
        self.assertEqual("MONOTONIC_SCHEDULER_GAP", continuity_failure(100, 100, 140, 140, 5))
        self.assertEqual("WALL_MONOTONIC_DIVERGENCE", continuity_failure(100, 100, 140, 100.1, 5))
        self.assertEqual("MONOTONIC_SCHEDULER_GAP", continuity_failure(100, 100, 100.1, 99, 5))
        self.assertIsNone(continuity_failure(100, 100, 100.1, 100.1, 5))

    def test_percentile_uses_ceiling_and_does_not_average_away_tail(self):
        self.assertEqual(95, percentile(list(range(1, 101))))
        self.assertEqual(1000, percentile([1] * 9 + [1000]))

    def test_refresh_uses_official_authority_and_persists_rotated_private_token(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tokens.private.json"
            atomic_json(path, {"accessToken": "synthetic-access-old", "refreshToken": "synthetic-refresh-old"})
            identity = Identity("http://127.0.0.1:18101", path)
            rotated = {"accessToken": "synthetic-access-new", "refreshToken": "synthetic-refresh-new"}
            with patch("phase6_continuous_observation.http", return_value=(200, {"code": 0, "data": rotated})) as call:
                identity.refresh()
            self.assertEqual("/api/auth/refresh", call.call_args.args[2])
            self.assertEqual("synthetic-refresh-old", call.call_args.kwargs["body"]["refreshToken"])
            self.assertEqual(rotated, json.loads(path.read_text()))
            self.assertEqual("synthetic-access-new", identity.access())
            self.assertEqual(1, identity.refresh_count)

    def test_failed_authority_refresh_never_forges_or_replaces_tokens(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tokens.private.json"
            original = {"accessToken": "synthetic-access", "refreshToken": "synthetic-refresh"}
            atomic_json(path, original)
            identity = Identity("http://127.0.0.1:18101", path)
            with patch("phase6_continuous_observation.http", return_value=(401, {"code": 40100})):
                with self.assertRaisesRegex(RuntimeError, "OFFICIAL_TOKEN_REFRESH_FAILED"):
                    identity.refresh()
            self.assertEqual(original, json.loads(path.read_text()))


if __name__ == "__main__":
    unittest.main()
