import json
from contextlib import contextmanager
import http.client
import http.server
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from phase6_continuous_observation import (
    Identity, Observer, MAX_RESPONSE_BYTES, atomic_json, continuity_failure, percentile,
    http as observation_http,
)


@contextmanager
def local_server(handler):
    with http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield server
        finally:
            server.shutdown()
            thread.join(timeout=2)


class QuietHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def reply(self, status, body, headers=None):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass


def tracked_connections():
    instances = []

    class TrackedConnection(http.client.HTTPConnection):
        def __init__(self, *args, **kwargs):
            self.close_calls = 0
            self.responses = []
            super().__init__(*args, **kwargs)
            instances.append(self)

        def getresponse(self):
            response = super().getresponse()
            self.responses.append(response)
            return response

        def close(self):
            self.close_calls += 1
            super().close()

    return instances, TrackedConnection


class ContinuousObservationTest(unittest.TestCase):
    def test_loopback_requests_never_create_tls_or_use_proxy_and_close_each_connection(self):
        received = []

        class Handler(QuietHandler):
            def do_GET(self):
                received.append(self.headers.get("Authorization"))
                self.reply(200, b'{"ok":true}')

        instances, connection = tracked_connections()
        with local_server(Handler) as server:
            # Fail if a future refactor reintroduces urllib's unused HTTPS handler,
            # Windows trust-store loading or an inherited system proxy.
            with patch("phase6_continuous_observation.HTTPConnection", connection), \
                    patch("ssl.create_default_context", side_effect=AssertionError("TLS forbidden")) as tls, \
                    patch("ssl.SSLContext.load_default_certs", side_effect=AssertionError("certs forbidden")) as certs, \
                    patch("urllib.request.getproxies", side_effect=AssertionError("proxy forbidden")) as proxy, \
                    patch("urllib.request.build_opener", side_effect=AssertionError("opener forbidden")) as opener:
                for _ in range(2):
                    status, result = observation_http(
                        "http://127.0.0.1:" + str(server.server_port), "GET", "/health",
                        {"Authorization": "Bearer synthetic-local-token"})
                    self.assertEqual(200, status)
                    self.assertTrue(result["ok"])
                tls.assert_not_called()
                certs.assert_not_called()
                proxy.assert_not_called()
                opener.assert_not_called()
        self.assertEqual(["Bearer synthetic-local-token"] * 2, received)
        self.assertEqual(2, len(instances))
        for instance in instances:
            self.assertGreaterEqual(instance.close_calls, 1)
            self.assertIsNone(instance.sock)
            self.assertTrue(all(response.closed for response in instance.responses))

    def test_redirect_is_returned_without_forwarding_credentials_to_its_target(self):
        target_requests = []

        class Target(QuietHandler):
            def do_GET(self):
                target_requests.append(dict(self.headers))
                self.reply(200, b'{"unexpected":true}')

        with local_server(Target) as target:
            class Redirect(QuietHandler):
                def do_GET(self):
                    self.reply(302, b'{}', {"Location": "http://127.0.0.1:" + str(target.server_port) + "/capture"})

            with local_server(Redirect) as redirect:
                status, result = observation_http(
                    "http://127.0.0.1:" + str(redirect.server_port), "GET", "/redirect",
                    {"Authorization": "Bearer synthetic-local-token",
                     "X-Harness-User-Token": "synthetic-user-token"})
                self.assertEqual((302, {}), (status, result))
        self.assertEqual([], target_requests)

    def test_invalid_origins_and_absolute_request_targets_fail_before_network(self):
        invalid = [
            "https://127.0.0.1", "http://example.com", "http://127.0.0.1.example.com",
            "http://user@127.0.0.1", "http://user:password@localhost",
            "http://127.0.0.1/", "http://127.0.0.1/path", "http://127.0.0.1?q=x",
            "http://127.0.0.1#x", " http://127.0.0.1", "http://127.0.0.1\n",
            "http://127.0.0.1:0", "http://127.0.0.1:65536", "http://127.0.0.1:",
            "http://127.1", "http://2130706433", "http://[::1]", "http://localhost\\@example.com",
        ]
        with patch("phase6_continuous_observation.HTTPConnection") as connection:
            for origin in invalid:
                with self.subTest(origin=origin), self.assertRaisesRegex(ValueError, "LOCAL_LOOPBACK_ORIGIN_REQUIRED"):
                    observation_http(origin, "GET", "/health")
            for path in ["http://example.com", "//example.com/capture", "/x\r\nHost: example.com", "/x#fragment"]:
                with self.subTest(path=path), self.assertRaises(ValueError):
                    observation_http("http://localhost", "GET", path)
            connection.assert_not_called()

    def test_real_http_errors_close_connections_and_bound_response_size(self):
        class Handler(QuietHandler):
            def do_GET(self):
                if self.path == "/oversized":
                    self.close_connection = True
                    self.reply(200, b'"' + b"x" * MAX_RESPONSE_BYTES + b'"', {"Connection": "close"})
                elif self.path == "/oversized-no-length":
                    self.send_response(200)
                    self.send_header("Connection", "close")
                    self.end_headers()
                    self.close_connection = True
                    try:
                        self.wfile.write(b'"' + b"x" * MAX_RESPONSE_BYTES + b'"')
                    except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                        pass
                elif self.path == "/bad-json":
                    self.reply(200, b"not-json")
                elif self.path == "/truncated":
                    self.send_response(200)
                    self.send_header("Content-Length", "100")
                    self.end_headers()
                    self.wfile.write(b"{}")
                    self.close_connection = True
                elif self.path == "/bad-status":
                    self.wfile.write(b"INVALID STATUS\r\n\r\n")
                    self.close_connection = True

        instances, connection = tracked_connections()
        with local_server(Handler) as server, patch("phase6_continuous_observation.HTTPConnection", connection):
            origin = "http://127.0.0.1:" + str(server.server_port)
            for path, error in [
                ("/oversized", ValueError), ("/oversized-no-length", ValueError),
                ("/bad-json", ValueError),
                ("/truncated", http.client.IncompleteRead), ("/bad-status", http.client.BadStatusLine),
            ]:
                with self.subTest(path=path), self.assertRaises(error):
                    observation_http(origin, "GET", path)
        self.assertEqual(5, len(instances))
        for instance in instances:
            self.assertGreaterEqual(instance.close_calls, 1)
            self.assertIsNone(instance.sock)
            self.assertTrue(all(response.closed for response in instance.responses))

    def test_sample_uses_precise_counter_and_transport_error_preserves_unknown_write(self):
        observer = Observer.__new__(Observer)
        observer.tag = "synthetic-observation"
        observer.route = "/v1/projects/phase6-soak/runs"
        observer.origin = "http://127.0.0.1"
        observer.ref = {}
        observer.headers = lambda key: {}
        with patch("phase6_continuous_observation.time.monotonic", side_effect=AssertionError("coarse latency clock")), \
                patch("phase6_continuous_observation.time.perf_counter", side_effect=[10, 10.000123]), \
                patch("phase6_continuous_observation.http", return_value=(202, {"run": {"id": "synthetic-run"}})):
            result = observer.measure(0)
        self.assertEqual(0.123, result["latencyMillis"])
        self.assertEqual("synthetic-run", result["runId"])
        for failure in [http.client.BadStatusLine("invalid"), http.client.IncompleteRead(b"partial", 10)]:
            with self.subTest(failure=type(failure).__name__), \
                    patch("phase6_continuous_observation.http", side_effect=failure):
                result = observer.measure(10)
            self.assertEqual("POST", result["method"])
            self.assertEqual("synthetic-observation-10", result["key"])
            self.assertEqual(0, result["status"])
            self.assertIsNone(result["runId"])

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
