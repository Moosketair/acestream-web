import tempfile
import unittest
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
from unittest.mock import patch

from streaming import StreamManager, parse_content


HASH = "0123456789abcdef0123456789abcdef01234567"


class StreamingStartupTests(unittest.TestCase):
    def test_bare_hash_is_a_content_id_and_explicit_infohash_still_works(self):
        self.assertEqual(parse_content(HASH), ("id", HASH))
        self.assertEqual(parse_content("acestream://" + HASH), ("id", HASH))
        self.assertEqual(parse_content("acestream://?infohash=" + HASH),
                         ("infohash", HASH))
        self.assertEqual(parse_content("https://localhost/ace/getstream?id=" + HASH),
                         ("id", HASH))

    def test_ffmpeg_log_explains_and_redacts_http_errors(self):
        with tempfile.TemporaryDirectory() as d:
            logfile = Path(d) / "ffmpeg.log"
            logfile.write_text(
                "[http @ 0x000] HTTP error 404 Not Found\n"
                "[in#0 @ 0x000] Error opening input: http://gluetun:6878/ace/getstream?id=" + HASH,
                encoding="utf-8",
            )
            with self.assertLogs(level="WARNING") as captured:
                message = StreamManager._ffmpeg_failure_message(logfile, 1)
            self.assertIn("404", message)
            combined = " ".join(captured.output)
            self.assertNotIn(HASH, combined)
            self.assertIn("[REDACTED]", combined)

    def test_ffmpeg_failure_without_log_has_useful_message(self):
        with self.assertLogs(level="WARNING"):
            message = StreamManager._ffmpeg_failure_message(
                Path("/nonexistent/ffmpeg-diagnostic-missing.log"), 1)
        self.assertIn("Dockge", message)


if __name__ == "__main__":
    unittest.main()
