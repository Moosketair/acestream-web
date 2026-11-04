import unittest
from search_sources import extract_hash, parse_playlist, normalize
from streaming import parse_content

HASH_A='0123456789abcdef0123456789abcdef01234567'
HASH_B='fedcba9876543210fedcba9876543210fedcba98'


class PlaylistParserTests(unittest.TestCase):
    def test_parses_public_m3u_and_deduplicates(self):
        playlist=f'''#EXTM3U
#EXTINF:-1 tvg-name="Chaîne Démo" group-title="Sport",Fallback
http://127.0.0.1:6878/ace/getstream?id={HASH_A}
#EXTINF:-1 tvg-name="Doublon" group-title="Divers",Fallback
http://127.0.0.1:6878/ace/getstream?infohash={HASH_A}
#EXTINF:-1 group-title="Info",Autre chaîne
acestream://{HASH_B}
#EXTINF:-1,URL qui n'est pas un hash
http://www.example.org/?id=notahash
'''
        results = parse_playlist(playlist)
        self.assertEqual(len(results),2)
        self.assertEqual(results[0],{'name':'Chaîne Démo','category':'Sport','content_id':HASH_A,'source':'search-ace.stream'})
        self.assertEqual(results[1]['content_id'],HASH_B)
    def test_rejects_html_and_unrelated_links(self):
        with self.assertRaises(ValueError):parse_playlist('<html>not a playlist</html>')
        self.assertIsNone(extract_hash('file:///etc/passwd?id='+HASH_A))
        self.assertIsNone(extract_hash('http://127.0.0.1:6878/ace/getstream?id=123'))
    def test_parse_manual_links(self):
        self.assertEqual(parse_content(HASH_A),('infohash',HASH_A))
        self.assertEqual(parse_content('acestream://'+HASH_B),('infohash',HASH_B))
        self.assertEqual(parse_content('http://localhost/ace/getstream?id='+HASH_A),('id',HASH_A))
        self.assertEqual(parse_content('http://localhost/ace/getstream?content_id='+HASH_B),('content_id',HASH_B))
        self.assertEqual(parse_content('acestream://?infohash='+HASH_B),('infohash',HASH_B))
        self.assertEqual(parse_content('garbage'),(None,None))
    def test_normalize_french(self):
        self.assertEqual(normalize('TÉLÉVISION'), 'television')

if __name__=='__main__':unittest.main()
