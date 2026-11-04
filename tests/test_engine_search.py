import unittest
from unittest.mock import patch
import requests

from search_sources import EngineSearch

GOOD_HASH = "0123456789abcdef0123456789abcdef01234567"
SECOND_HASH = "abcdef0123456789abcdef0123456789abcdef01"


class FakeResponse:
    def __init__(self, payload, status=200):
        self.payload = payload
        self.status = status

    def raise_for_status(self):
        if self.status >= 400:
            raise requests.HTTPError("invalid endpoint")

    def json(self):
        return self.payload


class EngineSearchTests(unittest.TestCase):
    def test_modern_api_grouped_and_ungrouped(self):
        payload = {"result": {"total": 2, "results": [
            {"name": "TV Info", "items": [
                {"infohash": GOOD_HASH.upper(), "name": "TV Info HD",
                 "categories": ["tv"], "status": 2, "availability": 0.95,
                 "bitrate": 1200000}]},
            {"infohash": SECOND_HASH, "name": "Radio HD",
             "categories": ["radio"], "status": 1},
            {"infohash": "NOT-A-HASH", "name": "Invalide"}]}, "error": None}
        with patch("search_sources.requests.get", return_value=FakeResponse(payload)) as http:
            results, total, warning = EngineSearch("http://gluetun:6878").search("tv")
        self.assertEqual(total, 2)
        self.assertIsNone(warning)
        self.assertEqual(results[0]["name"], "TV Info HD")
        self.assertEqual(results[0]["content_id"], GOOD_HASH)
        self.assertEqual(results[0]["playback_ref"], "acestream://?infohash="+GOOD_HASH)
        self.assertEqual(results[0]["status"], 2)
        self.assertEqual(results[0]["availability"], 0.95)
        self.assertEqual(results[1]["source"], "API Ace Stream")
        self.assertEqual(http.call_args.kwargs["params"]["query"], "tv")

    def test_legacy_fallback_never_returns_api_token_to_browser(self):
        def fake_get(url, params, **kwargs):
            if url.endswith("/search"):
                return FakeResponse({}, status=404)
            if params["method"] == "get_api_access_token":
                return FakeResponse({"result": {"token": "private-secret-token"}, "error": None})
            self.assertEqual(params["token"], "private-secret-token")
            return FakeResponse({"result": {"results": [
                {"infohash": GOOD_HASH, "name": "Canal", "categories": ["tv"]}],
                "total": 1}, "error": None})
        with patch("search_sources.requests.get", side_effect=fake_get):
            results, total, warning = EngineSearch("http://gluetun:6878").search("canal")
        self.assertEqual(total, 1)
        self.assertNotIn("token", results[0])
        self.assertIsNone(warning)

    def test_missing_api_is_handled(self):
        with patch("search_sources.requests.get", side_effect=requests.ConnectionError):
            with self.assertRaisesRegex(RuntimeError, "indisponible"):
                EngineSearch("http://gluetun:6878").search("abc")

    def test_bad_payload_is_rejected(self):
        with self.assertRaises(ValueError):
            EngineSearch._parse({"result": {"results": "not a list"}}, 60)


if __name__ == "__main__":
    unittest.main()
