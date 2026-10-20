import json
import concurrent.futures
import errno
from contextlib import contextmanager, redirect_stdout
import http.client
import http.server
import io
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from phase6_continuous_observation import (
    Identity, Observer, LoopbackTransport, MAX_RESPONSE_BYTES, MAX_CONNECTION_IDLE_SECONDS,
    atomic_json, continuity_failure, percentile, safe_error_numbers, http as observation_http,
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
            self.connect_calls = 0
            self.request_calls = 0
            self.thread_id = threading.get_ident()
            self.responses = []
            super().__init__(*args, **kwargs)
            instances.append(self)

        def connect(self):
            self.connect_calls += 1
            return super().connect()

        def request(self, *args, **kwargs):
            self.request_calls += 1
            return super().request(*args, **kwargs)

        def getresponse(self):
            response = super().getresponse()
            self.responses.append(response)
            return response

        def close(self):
            self.close_calls += 1
            super().close()

    return instances, TrackedConnection


def observation_fixture(directory, origin="http://127.0.0.1:1", duration=60):
    root = Path(directory)
    atomic_json(root / "tokens.json", {
        "accessToken": "SYNTHETIC_PRIVATE_ACCESS", "refreshToken": "SYNTHETIC_PRIVATE_REFRESH"})
    (root / "credential.txt").write_text("SYNTHETIC_PRIVATE_APPLICATION")
    reference = {"agentId": "synthetic", "releaseId": "synthetic-release", "digest": "synthetic-digest"}
    atomic_json(root / "deployment.json", {"releases": [{
        "projectId": "phase6-soak", "releaseId": reference["releaseId"],
        "workflow": {"start": "end", "nodes": {"end": {"kind": "end", "output": "value"}}},
    }]})
    atomic_json(root / "config.json", {
        "origin": origin, "authOrigin": origin, "project": "phase6-soak",
        "tokenFile": str(root / "tokens.json"), "applicationCredentialFile": str(root / "credential.txt"),
        "releaseRef": reference, "deploymentFile": str(root / "deployment.json"),
        "reportDirectory": str(root / "reports"), "processRecordFiles": [],
    })
    return Observer(root / "config.json", duration)


def healthy_service():
    return {"available": True, "workers": [{"workerId": "first"}, {"workerId": "second"}],
            "workerSetChanged": False, "unresolvedPermits": 0}


def complete_minute(number):
    return {"window": number, "at": "2026-10-20T00:00:00Z", "elapsedMonotonicSeconds": number * 60,
            "requests": 600, "getRequests": 540, "postRequests": 60, "errors": 0,
            "p95Millis": 100, "getP95Millis": 100, "postP95Millis": 100,
            "completeMinute": True, "service": healthy_service()}


def supply_completed_evidence(observer, minutes):
    observer.windows = [complete_minute(i + 1) for i in range(minutes)]
    observer.total = {"requests": minutes * 600, "getRequests": minutes * 540,
                      "postRequests": minutes * 60, "errors": 0}
    observer.db.executemany("INSERT INTO commands VALUES(?,?,?,?,?)", (
        (i * 10, "synthetic-key-" + str(i), "synthetic-run-" + str(i), 202, 100)
        for i in range(minutes * 60)))
    observer.db.commit()


def finish_with_clock(observer, elapsed, previous_elapsed=None):
    previous_elapsed = elapsed - .1 if previous_elapsed is None else previous_elapsed
    observer.last_mono = observer.start + previous_elapsed
    observer.last_wall = observer.start_wall + previous_elapsed
    with patch("phase6_continuous_observation.time.monotonic", return_value=observer.start + elapsed), \
            patch("phase6_continuous_observation.time.time", return_value=observer.start_wall + elapsed), \
            redirect_stdout(io.StringIO()):
        result = observer.run()
    return result, json.loads((observer.directory / "report.json").read_text())


class ContinuousObservationTest(unittest.TestCase):
    def test_pool_reuses_fully_consumed_responses_and_current_request_headers(self):
        received = []

        class Handler(QuietHandler):
            def do_GET(self):
                received.append(self.headers.get("Authorization"))
                self.reply(200, b'{"items":[]}')

        transport = LoopbackTransport()
        instances, connection = tracked_connections()
        with local_server(Handler) as server, patch("phase6_continuous_observation.HTTPConnection", connection):
            try:
                for number in range(8):
                    status, reply = observation_http(
                        "http://127.0.0.1:" + str(server.server_port), "GET", "/runs",
                        {"Authorization": "synthetic-token-" + str(number)}, transport=transport)
                    self.assertEqual((200, {"items": []}), (status, reply))
                snapshot = transport.snapshot()
                self.assertEqual((1, 7, 0, 1),
                                 tuple(snapshot[key] for key in ("opened", "reused", "discarded", "active")))
                self.assertTrue(snapshot["threadLocal"])
                self.assertTrue(snapshot["noRetry"])
                self.assertEqual(5, snapshot["maxIdleSeconds"])
            finally:
                transport.close_all()
        self.assertEqual(["synthetic-token-" + str(i) for i in range(8)], received)
        self.assertEqual(1, len(instances))
        self.assertEqual(1, instances[0].connect_calls)
        self.assertEqual(8, instances[0].request_calls)
        self.assertTrue(all(response.closed for response in instances[0].responses))
        self.assertIsNone(instances[0].sock)
        self.assertEqual(0, transport.snapshot()["active"])
        self.assertEqual(1, transport.snapshot()["discarded"])
        transport.close_all()
        self.assertEqual(1, transport.snapshot()["discarded"])
        with self.assertRaisesRegex(RuntimeError, "TRANSPORT_CLOSED"):
            observation_http("http://127.0.0.1:1", "GET", "/", transport=transport)

    def test_pool_isolates_threads_and_origins_and_closes_connections_from_finished_threads(self):
        class Handler(QuietHandler):
            def do_GET(self):
                self.reply(200, b'{}')

        transport = LoopbackTransport()
        barrier = threading.Barrier(2)
        instances, connection = tracked_connections()
        with local_server(Handler) as first, local_server(Handler) as second, \
                patch("phase6_continuous_observation.HTTPConnection", connection):
            origins = ["http://127.0.0.1:" + str(server.server_port) for server in (first, second)]

            def worker():
                for origin in origins:
                    observation_http(origin, "GET", "/", transport=transport)
                barrier.wait(timeout=5)
                for origin in origins:
                    observation_http(origin, "GET", "/", transport=transport)

            try:
                with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                    futures = [pool.submit(worker) for _ in range(2)]
                    for future in futures:
                        future.result(timeout=10)
                self.assertEqual(4, len(instances))
                self.assertEqual(2, len({instance.thread_id for instance in instances}))
                self.assertEqual(4, transport.snapshot()["opened"])
                self.assertEqual(4, transport.snapshot()["reused"])
                self.assertEqual(4, transport.snapshot()["active"])
                self.assertTrue(all(instance.connect_calls == 1 and instance.request_calls == 2
                                    for instance in instances))
            finally:
                transport.close_all()
        self.assertTrue(all(instance.sock is None for instance in instances))
        self.assertEqual(4, transport.snapshot()["discarded"])

    def test_idle_expiry_replaces_connection_before_send_without_replaying_a_request(self):
        calls = []

        class Handler(QuietHandler):
            def do_GET(self):
                calls.append(self.path)
                self.reply(200, b'{}')

        transport = LoopbackTransport()
        instances, connection = tracked_connections()
        with local_server(Handler) as server, patch("phase6_continuous_observation.HTTPConnection", connection):
            origin = "http://127.0.0.1:" + str(server.server_port)
            try:
                observation_http(origin, "GET", "/first", transport=transport)
                future_clock = time.monotonic() + MAX_CONNECTION_IDLE_SECONDS + 1
                with patch("phase6_continuous_observation.time.monotonic", return_value=future_clock):
                    observation_http(origin, "GET", "/next", transport=transport)
                self.assertEqual(2, transport.snapshot()["opened"])
                self.assertEqual(0, transport.snapshot()["reused"])
                self.assertEqual(1, transport.snapshot()["discarded"])
                self.assertIsNone(instances[0].sock)
                self.assertEqual([1, 1], [instance.request_calls for instance in instances])
            finally:
                transport.close_all()
        self.assertEqual(["/first", "/next"], calls)

    def test_server_declared_close_is_not_retained(self):
        class Handler(QuietHandler):
            def do_GET(self):
                self.reply(200, b'{}', {"Connection": "close"})

        transport = LoopbackTransport()
        instances, connection = tracked_connections()
        with local_server(Handler) as server, patch("phase6_continuous_observation.HTTPConnection", connection):
            try:
                for _ in range(2):
                    self.assertEqual((200, {}), observation_http(
                        "http://127.0.0.1:" + str(server.server_port), "GET", "/", transport=transport))
                self.assertEqual(2, len(instances))
                self.assertEqual(0, transport.snapshot()["active"])
                self.assertEqual(0, transport.snapshot()["reused"])
                self.assertEqual(2, transport.snapshot()["discarded"])
            finally:
                transport.close_all()

    def test_stale_keepalive_failure_is_counted_once_and_only_next_sample_connects(self):
        calls = []
        closed = threading.Event()

        class Handler(QuietHandler):
            def do_GET(self):
                calls.append(self.path)
                self.reply(200, b'{"items":[]}')
                if len(calls) == 1:
                    # Deliberately omit Connection: close to simulate an idle peer close.
                    self.close_connection = True

            def finish(self):
                super().finish()
                closed.set()

        instances, connection = tracked_connections()
        with tempfile.TemporaryDirectory() as directory, local_server(Handler) as server, \
                patch("phase6_continuous_observation.HTTPConnection", connection):
            observer = observation_fixture(directory, "http://127.0.0.1:" + str(server.server_port))
            try:
                observer.collect(observer.measure(1))
                self.assertTrue(closed.wait(timeout=5))
                observer.collect(observer.measure(2))
                self.assertEqual(1, observer.total["errors"])
                self.assertEqual(1, len(instances))
                self.assertEqual(2, instances[0].request_calls)
                self.assertEqual(1, instances[0].connect_calls)
                self.assertEqual(0, observer.transport.snapshot()["active"])
                observer.collect(observer.measure(3))
                self.assertEqual(1, observer.total["errors"])
                self.assertEqual(2, len(instances))
                self.assertEqual(2, len(calls))
                rows = (observer.directory / "diagnostics.jsonl").read_text().splitlines()
                self.assertEqual(1, len(rows))
                row = json.loads(rows[0])
                self.assertEqual(2, row["sequence"])
                self.assertEqual(0, row["status"])
                self.assertIn(row["phase"], ("SEND", "RESPONSE_HEADERS"))
                self.assertIs(observer.report["qualityPassed"], False)
            finally:
                observer.transport.close_all()
                observer.db.close()

    def test_dropped_post_response_preserves_unknown_write_and_never_resends_it(self):
        received_keys = []

        class Handler(QuietHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"]))
                received_keys.append(self.headers["Idempotency-Key"])
                if len(received_keys) == 1:
                    self.close_connection = True
                else:
                    self.reply(202, b'{"run":{"id":"synthetic-second-run"}}')

        with tempfile.TemporaryDirectory() as directory, local_server(Handler) as server:
            observer = observation_fixture(directory, "http://127.0.0.1:" + str(server.server_port))
            try:
                for sequence in (0, 10):
                    key = observer.tag + "-" + str(sequence)
                    observer.db.execute("INSERT INTO commands VALUES(?,?,NULL,-1,0)", (sequence, key))
                    observer.collect(observer.measure(sequence))
                rows = observer.db.execute(
                    "SELECT sequence,status,run_id FROM commands ORDER BY sequence").fetchall()
                self.assertEqual([(0, 0, None), (10, 202, "synthetic-second-run")], rows)
                self.assertEqual([observer.tag + "-0", observer.tag + "-10"], received_keys)
                self.assertEqual(1, observer.total["errors"])
                self.assertEqual(2, observer.transport.snapshot()["opened"])
                row = json.loads((observer.directory / "diagnostics.jsonl").read_text())
                self.assertEqual("RESPONSE_HEADERS", row["phase"])
                self.assertEqual(0, row["sequence"])
            finally:
                observer.transport.close_all()
                observer.db.close()

    def test_connect_failure_keeps_safe_numeric_codes_and_cleanup_cannot_mask_it(self):
        failure = OSError(errno.EADDRNOTAVAIL, "SYNTHETIC_PRIVATE_EXCEPTION")
        failure.winerror = 10048
        instances = []

        class FailingConnection(http.client.HTTPConnection):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                self.connect_calls = self.send_calls = self.close_calls = 0
                instances.append(self)

            def connect(self):
                self.connect_calls += 1
                raise failure

            def request(self, *args, **kwargs):
                self.send_calls += 1
                raise AssertionError("No request before connect")

            def close(self):
                self.close_calls += 1
                super().close()
                raise OSError(errno.ECONNRESET, "SYNTHETIC_PRIVATE_CLEANUP")

        with tempfile.TemporaryDirectory() as directory, \
                patch("phase6_continuous_observation.HTTPConnection", FailingConnection):
            observer = observation_fixture(directory)
            try:
                observer.collect(observer.measure(1))
                text = (observer.directory / "diagnostics.jsonl").read_text()
                row = json.loads(text)
                self.assertEqual("CONNECT", row["phase"])
                self.assertEqual(errno.EADDRNOTAVAIL, row["errno"])
                self.assertEqual(10048, row["winerror"])
                self.assertEqual("TRANSPORT_ERROR", row["code"])
                self.assertEqual(0, row["status"])
                self.assertNotIn("SYNTHETIC_PRIVATE", text)
                self.assertEqual((1, 0, 1), (instances[0].connect_calls,
                                           instances[0].send_calls, instances[0].close_calls))
                self.assertEqual(0, observer.transport.snapshot()["active"])
            finally:
                observer.transport.close_all()
                observer.db.close()

    def test_send_error_has_fixed_phase_and_no_implicit_reconnect(self):
        class Handler(QuietHandler):
            def do_GET(self):
                raise AssertionError("Request must fail before transmission")

        instances, tracked = tracked_connections()

        class FailingSend(tracked):
            def request(self, *args, **kwargs):
                self.request_calls += 1
                raise BrokenPipeError(errno.EPIPE, "SYNTHETIC_PRIVATE_SEND")

        with tempfile.TemporaryDirectory() as directory, local_server(Handler) as server, \
                patch("phase6_continuous_observation.HTTPConnection", FailingSend):
            observer = observation_fixture(directory, "http://127.0.0.1:" + str(server.server_port))
            try:
                observer.collect(observer.measure(1))
                row = json.loads((observer.directory / "diagnostics.jsonl").read_text())
                self.assertEqual("SEND", row["phase"])
                self.assertEqual(errno.EPIPE, row["errno"])
                self.assertEqual("BrokenPipeError", row["exceptionClass"])
                self.assertEqual(1, instances[0].request_calls)
                self.assertEqual(1, instances[0].connect_calls)
                self.assertEqual(0, instances[0].auto_open)
                self.assertIsNone(instances[0].sock)
            finally:
                observer.transport.close_all()
                observer.db.close()

    def test_numeric_and_phase_allowlists_reject_private_strings_bools_and_unknown_values(self):
        with tempfile.TemporaryDirectory() as directory:
            observer = observation_fixture(directory)
            try:
                for number in ("SYNTHETIC_PRIVATE_NUMBER", True, 999999, None):
                    failure = OSError()
                    failure.errno = failure.winerror = number
                    self.assertEqual({"errno": None, "winerror": None}, safe_error_numbers(failure))
                    observer.record_diagnostic(
                        "synthetic-time", 1, "GET", 0, "OSError", 0, "TRANSPORT_ERROR",
                        phase="SYNTHETIC_PRIVATE_PHASE", errno=number, winerror=number)
                text = (observer.directory / "diagnostics.jsonl").read_text()
                self.assertNotIn("SYNTHETIC_PRIVATE", text)
                for line in text.splitlines():
                    row = json.loads(line)
                    self.assertEqual("OBSERVER", row["phase"])
                    self.assertIsNone(row["errno"])
                    self.assertIsNone(row["winerror"])
            finally:
                observer.transport.close_all()
                observer.db.close()

    def test_preflight_failure_closes_transport(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(Observer, "validate_release",
                             side_effect=ValueError("SYNTHETIC_RELEASE_NOT_IN_DEPLOYMENT")), \
                patch.object(LoopbackTransport, "close_all", autospec=True,
                             side_effect=LoopbackTransport.close_all) as close:
            with self.assertRaisesRegex(ValueError, "SYNTHETIC_RELEASE_NOT_IN_DEPLOYMENT"):
                observation_fixture(directory)
            close.assert_called_once()
            transport = close.call_args.args[0]
            self.assertTrue(transport.closed)
            report_path = next((Path(directory) / "reports").glob("*/report.json"))
            report = json.loads(report_path.read_text())
            self.assertEqual("FAILED_PREFLIGHT", report["status"])
            self.assertEqual(0, report["transport"]["active"])

    def test_http_error_status_and_parse_failure_are_durable_without_private_content(self):
        replies = [(503, b"<html>SYNTHETIC_PRIVATE_BODY http://private.invalid</html>"),
                   (200, b"SYNTHETIC_PRIVATE_BAD_JSON"), (200, b"[]"), (200, b'{"items":[]}')]

        class Handler(QuietHandler):
            def do_GET(self):
                status, body = replies.pop(0)
                self.reply(status, body)

        with tempfile.TemporaryDirectory() as directory, local_server(Handler) as server:
            observer = observation_fixture(directory, "http://127.0.0.1:" + str(server.server_port))
            try:
                for sequence in (1, 2, 3, 4):
                    observer.collect(observer.measure(sequence))
                    report = json.loads((observer.directory / "report.json").read_text())
                    self.assertIs(report["qualityPassed"], False)
                    self.assertEqual(1, report["firstQualityFailure"]["sequence"])
                diagnostics = [json.loads(line) for line in (observer.directory / "diagnostics.jsonl").read_text().splitlines()]
                self.assertEqual([503, 200, 200], [row["status"] for row in diagnostics])
                self.assertEqual(["JSONDecodeError", "JSONDecodeError", None], [row["exceptionClass"] for row in diagnostics])
                self.assertEqual(["RESPONSE_INVALID_JSON", "RESPONSE_INVALID_JSON", "RESPONSE_INVALID_SHAPE"],
                                 [row["code"] for row in diagnostics])
                for row in diagnostics:
                    self.assertEqual({"at", "sequence", "method", "status", "exceptionClass",
                                      "latencyMillis", "code", "phase", "errno", "winerror"}, set(row))
                self.assertEqual(["DECODE_JSON", "DECODE_JSON", "RESPONSE_VALIDATION"],
                                 [row["phase"] for row in diagnostics])
                observer.flush_window(complete_minute=True, service=healthy_service())
                self.assertEqual(3, observer.windows[0]["errors"])
                self.assertIs(observer.report["qualityPassed"], False)
                text = (observer.directory / "diagnostics.jsonl").read_text() + (observer.directory / "report.json").read_text()
                self.assertNotIn("SYNTHETIC_PRIVATE", text)
                self.assertNotIn("private.invalid", text)
            finally:
                observer.transport.close_all()
                observer.db.close()

    def test_malformed_accepted_write_preserves_key_without_attribute_error(self):
        replies = [b"null", b"[]", b'{"run":null}', b'{"run":{"id":[]}}']

        class Handler(QuietHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", 0)))
                self.reply(202, replies.pop(0))

        with tempfile.TemporaryDirectory() as directory, local_server(Handler) as server:
            observer = observation_fixture(directory, "http://127.0.0.1:" + str(server.server_port))
            try:
                for sequence in (0, 10, 20, 30):
                    key = observer.tag + "-" + str(sequence)
                    observer.db.execute("INSERT INTO commands VALUES(?,?,NULL,-1,0)", (sequence, key))
                    observer.db.commit()
                    observer.collect(observer.measure(sequence))
                self.assertEqual(4, observer.total["errors"])
                rows = observer.db.execute("SELECT status,run_id,request_key FROM commands").fetchall()
                self.assertTrue(all(status == 202 and run_id is None and key for status, run_id, key in rows))
                diagnostics = [json.loads(line) for line in (observer.directory / "diagnostics.jsonl").read_text().splitlines()]
                self.assertEqual([202] * 4, [row["status"] for row in diagnostics])
                self.assertTrue(all(row["code"] in ("RESPONSE_INVALID_SHAPE", "RESPONSE_RUN_ID_MISSING") for row in diagnostics))
            finally:
                observer.transport.close_all()
                observer.db.close()

    def test_bad_status_line_and_truncated_success_record_safe_transport_diagnostics(self):
        calls = []

        class Handler(QuietHandler):
            def do_GET(self):
                calls.append(True)
                self.close_connection = True
                if len(calls) == 1:
                    self.wfile.write(b"SYNTHETIC_PRIVATE_INVALID_STATUS\r\n\r\n")
                else:
                    self.send_response(200)
                    self.send_header("Content-Length", "100")
                    self.end_headers()
                    self.wfile.write(b'{"items":[]}')

        with tempfile.TemporaryDirectory() as directory, local_server(Handler) as server:
            observer = observation_fixture(directory, "http://127.0.0.1:" + str(server.server_port))
            try:
                observer.collect(observer.measure(1))
                observer.collect(observer.measure(2))
                text = (observer.directory / "diagnostics.jsonl").read_text()
                rows = [json.loads(line) for line in text.splitlines()]
                self.assertEqual([0, 200], [row["status"] for row in rows])
                self.assertEqual(["BadStatusLine", "IncompleteRead"], [row["exceptionClass"] for row in rows])
                self.assertEqual(["HTTP_PROTOCOL_ERROR", "RESPONSE_TRUNCATED"], [row["code"] for row in rows])
                self.assertEqual(["RESPONSE_HEADERS", "RESPONSE_BODY"], [row["phase"] for row in rows])
                self.assertNotIn("SYNTHETIC_PRIVATE", text)
            finally:
                observer.transport.close_all()
                observer.db.close()

    def test_real_response_timeout_retains_status_and_persists_exception_type(self):
        class Handler(QuietHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Length", "12")
                self.end_headers()
                time.sleep(.1)
                self.close_connection = True

        class ShortTimeoutConnection(http.client.HTTPConnection):
            def __init__(self, *args, **kwargs):
                kwargs["timeout"] = .03
                super().__init__(*args, **kwargs)

        with tempfile.TemporaryDirectory() as directory, local_server(Handler) as server, \
                patch("phase6_continuous_observation.HTTPConnection", ShortTimeoutConnection):
            observer = observation_fixture(directory, "http://127.0.0.1:" + str(server.server_port))
            try:
                observer.collect(observer.measure(1))
                row = json.loads((observer.directory / "diagnostics.jsonl").read_text())
                self.assertEqual(200, row["status"])
                self.assertEqual("TimeoutError", row["exceptionClass"])
                self.assertEqual("TRANSPORT_TIMEOUT", row["code"])
                self.assertEqual("RESPONSE_BODY", row["phase"])
                self.assertEqual(1, observer.total["errors"])
            finally:
                observer.transport.close_all()
                observer.db.close()

    def test_failed_telemetry_preserves_window_and_marks_quality_immediately(self):
        class Handler(QuietHandler):
            def do_GET(self):
                if self.path.endswith("/operations"):
                    self.reply(502, b"SYNTHETIC_PRIVATE_BAD_GATEWAY")
                else:
                    self.reply(200, b'{"items":[]}')

        with tempfile.TemporaryDirectory() as directory, local_server(Handler) as server:
            observer = observation_fixture(directory, "http://127.0.0.1:" + str(server.server_port))
            try:
                observer.collect(observer.measure(1))
                observer.flush_window(complete_minute=True)
                row = json.loads((observer.directory / "windows.jsonl").read_text())
                self.assertEqual(1, row["requests"])
                self.assertEqual(0, row["errors"])
                self.assertFalse(row["service"]["available"])
                self.assertEqual(502, row["service"]["status"])
                self.assertIs(json.loads((observer.directory / "report.json").read_text())["qualityPassed"], False)
            finally:
                observer.transport.close_all()
                observer.db.close()

    def test_slow_success_fails_only_the_window_p95_and_failure_is_sticky(self):
        calls = []

        class Handler(QuietHandler):
            def do_GET(self):
                calls.append(True)
                if len(calls) == 1:
                    time.sleep(.55)
                self.reply(200, b'{"items":[]}')

        with tempfile.TemporaryDirectory() as directory, local_server(Handler) as server:
            observer = observation_fixture(directory, "http://127.0.0.1:" + str(server.server_port))
            try:
                observer.collect(observer.measure(1))
                self.assertIsNone(observer.report["qualityPassed"])
                observer.flush_window(complete_minute=True, service=healthy_service())
                report = json.loads((observer.directory / "report.json").read_text())
                self.assertEqual("WINDOW_P95_EXCEEDED", report["firstQualityFailure"]["code"])
                self.assertEqual(1, report["firstQualityFailure"]["window"])
                observer.collect(observer.measure(2))
                observer.flush_window(complete_minute=True, service=healthy_service())
                self.assertIs(observer.report["qualityPassed"], False)
                self.assertEqual(report["firstQualityFailure"], observer.report["firstQualityFailure"])
            finally:
                observer.transport.close_all()
                observer.db.close()

    def test_suspend_crossing_deadline_is_failed_even_with_otherwise_complete_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            observer = observation_fixture(directory, duration=86400)
            supply_completed_evidence(observer, 1440)
            result, report = finish_with_clock(observer, 86410, previous_elapsed=86300)
            self.assertEqual(1, result)
            self.assertEqual("FAILED", report["status"])
            self.assertEqual("MONOTONIC_SCHEDULER_GAP", report["failureCode"])
            self.assertFalse(report["continuous"])
            self.assertFalse(report["completed24Hours"])
            self.assertIs(report["qualityPassed"], False)

    def test_interruption_retains_final_partial_window_without_extra_network_requests(self):
        calls = []

        class Handler(QuietHandler):
            def do_GET(self):
                calls.append(self.path)
                self.reply(200, b'{"items":[]}')

        with tempfile.TemporaryDirectory() as directory, local_server(Handler) as server:
            observer = observation_fixture(directory, "http://127.0.0.1:" + str(server.server_port))
            observer.collect(observer.measure(1))
            result, report = finish_with_clock(observer, 120, previous_elapsed=1)
            self.assertEqual(1, result)
            self.assertEqual("FAILED", report["status"])
            row = json.loads((observer.directory / "windows.jsonl").read_text())
            self.assertEqual(1, row["requests"])
            self.assertFalse(row["completeMinute"])
            self.assertFalse(row["service"]["available"])
            self.assertEqual(1, len(calls))

    def test_no_windows_short_coverage_or_missing_requests_cannot_pass_after_deadline(self):
        for minutes, remove_request in [(0, False), (1439, False), (1440, True)]:
            with self.subTest(minutes=minutes, remove_request=remove_request), tempfile.TemporaryDirectory() as directory:
                observer = observation_fixture(directory, duration=86400)
                supply_completed_evidence(observer, minutes)
                if remove_request:
                    observer.total["requests"] -= 1
                    observer.total["getRequests"] -= 1
                result, report = finish_with_clock(observer, 86400)
                self.assertEqual(1, result)
                self.assertEqual("FAILED_SERVICE_TARGET", report["status"])
                self.assertIs(report["qualityPassed"], False)
                self.assertEqual("INCOMPLETE_OBSERVATION_COVERAGE", report["firstQualityFailure"]["code"])

    def test_normal_complete_deadline_can_pass_only_with_all_evidence_and_receipts(self):
        with tempfile.TemporaryDirectory() as directory:
            observer = observation_fixture(directory, duration=86400)
            supply_completed_evidence(observer, 1440)
            result, report = finish_with_clock(observer, 86400)
            self.assertEqual(0, result)
            self.assertEqual("PASSED", report["status"])
            self.assertTrue(report["completed24Hours"])
            self.assertTrue(report["qualityPassed"])
            self.assertEqual(1440, report["completedMinuteWindows"])
            self.assertEqual(864000, report["requests"]["requests"])
            self.assertEqual(0, report["unreconciledCommands"])

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
        observer.transport = None
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

    def test_invalid_refresh_schema_cannot_crash_or_replace_the_previous_session(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tokens.json"
            original = {"accessToken": "synthetic-access", "refreshToken": "synthetic-refresh"}
            atomic_json(path, original)
            identity = Identity("http://127.0.0.1:18101", path)
            for reply in [None, [], {"code": 0, "data": None},
                          {"code": 0, "data": {"accessToken": "new-without-refresh"}}]:
                with self.subTest(reply=reply), \
                        patch("phase6_continuous_observation.http", return_value=(200, reply)):
                    with self.assertRaisesRegex(RuntimeError, "OFFICIAL_TOKEN_REFRESH_FAILED"):
                        identity.refresh()
                self.assertEqual(original, json.loads(path.read_text()))


if __name__ == "__main__":
    unittest.main()
