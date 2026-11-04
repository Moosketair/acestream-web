"""Tests for the direct search-ace.stream Web catalogue JSON endpoint."""
import unittest
from unittest.mock import patch

import requests

from search_sources import SiteSearch, SITE_SEARCH_URL

HASH_1 = "0123456789abcdef0123456789abcdef01234567"
HASH_2 = "abcdef0123456789abcdef0123456789abcdef01"


class Response:
    def __init__(self, content, status_code=200):
        self.content = content
        self.status_code = status_code

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def iter_content(self, chunk_size=16384):
        for start in range(0, len(self.content), chunk_size):
            yield self.content[start:start + chunk_size]


class SiteSearchTests(unittest.TestCase):
    def test_rennes_search_matches_site_format_and_keeps_two_languages(self):
        payload = (f'[{{"content_id":"{HASH_1}","name":"Rennes - Olympiacos (English)",'
                   f'"translated_name":"Rennes - Olympiacos"}},'
                   f'{{"content_id":"{HASH_2}","name":"Rennes - Olympiacos (Russian)"}},'
                   '{"content_id":"nothex","name":"Invalid"},'
                   f'{{"content_id":"{HASH_1}","name":"Duplicate"}}]').encode()
        with patch("search_sources.requests.get", return_value=Response(payload)) as fake:
            results, total, warning = SiteSearch().search("rennes")
        self.assertEqual(total, 2)
        self.assertIsNone(warning)
        self.assertEqual(results[0]["content_id"], HASH_1)
        self.assertEqual(results[0]["name"], "Rennes - Olympiacos (English)")
        self.assertEqual(results[0]["translated_name"], "Rennes - Olympiacos")
        self.assertEqual(results[1]["name"], "Rennes - Olympiacos (Russian)")
        self.assertEqual(results[1]["source"], "Site search-ace.stream")
        self.assertEqual(fake.call_args.args[0], SITE_SEARCH_URL)
        self.assertEqual(fake.call_args.kwargs["params"], {"query": "rennes"})
        self.assertFalse(fake.call_args.kwargs["allow_redirects"])

    def test_invalid_items_and_control_characters_are_removed(self):
        payload = (f'[null, 23, {{"content_id":"{HASH_1.upper()}",'
                   '"name":"\u202eRennes\u0000 live"}}]').encode()
        with patch("search_sources.requests.get", return_value=Response(payload)):
            results, _total, _warning = SiteSearch().search("  ren\u202enes  ")
        self.assertEqual(results[0]["content_id"], HASH_1)
        self.assertEqual(results[0]["name"], "Rennes live")

    def test_oversize_redirect_and_unavailable_are_rejected(self):
        for response in (Response(b"[]", 302), Response(b"x" * (256 * 1024 + 1))):
            with self.subTest(status=response.status_code):
                with patch("search_sources.requests.get", return_value=response):
                    with self.assertRaises(RuntimeError):
                        SiteSearch().search("rennes")
        with patch("search_sources.requests.get", side_effect=requests.ConnectionError):
            with self.assertRaises(RuntimeError):
                SiteSearch().search("rennes")

    def test_rejects_non_list_json(self):
        with patch("search_sources.requests.get", return_value=Response(b'{"results": []}')):
            with self.assertRaises(RuntimeError):
                SiteSearch().search("rennes")


if __name__ == "__main__":
    unittest.main()
