import os
import tempfile
import unittest
from unittest.mock import patch

# app.py constructs the default WSGI instance on import; give it a writable path.
os.environ.setdefault('DATA_DIR', tempfile.mkdtemp(prefix='acestream-ci-default-'))
os.environ.setdefault('SETUP_TOKEN', 'testonly-_secret-key-of-32-characters-abcd')
from app import create_app

HASH='0123456789abcdef0123456789abcdef01234567'

class DashboardTests(unittest.TestCase):
    def setUp(self):
        self.tempdir=tempfile.TemporaryDirectory()
        self.app=create_app({'TESTING':True,'DATA_DIR':self.tempdir.name,'TEST_SECRET':'a-test-only-secret',
                             'SETUP_TOKEN':'testonly-_secret-key-of-32-characters-abcd'})
        self.client=self.app.test_client()
        self.csrf=self.client.get('/api/auth/session').get_json()['csrf']

    def tearDown(self):
        self.tempdir.cleanup()

    def post(self,path,data=None,client=None,token=None,method='POST'):
        return (client or self.client).open(path,method=method,json=data,
               headers={'X-CSRF-Token':token or self.csrf})

    def setup_admin(self):
        resp=self.post('/api/auth/setup',{'setup_token':'testonly-_secret-key-of-32-characters-abcd',
            'username':'Moosketair','password':'superlongtestpassword!'})
        self.assertEqual(resp.status_code,201,resp.get_data(as_text=True))
        self.csrf=resp.get_json()['csrf']

    def test_first_run_setup_then_login(self):
        self.assertFalse(self.client.get('/api/auth/session').get_json()['configured'])
        self.assertEqual(self.client.get('/api/favorites').status_code,401)
        self.assertEqual(self.post('/api/auth/setup',{'setup_token':'incorrect','username':'moosketair','password':'passpasspasspass'}).status_code,403)
        self.setup_admin()
        self.assertEqual(self.client.get('/api/auth/session').get_json()['user']['role'],'admin')
        self.assertEqual(self.post('/api/auth/setup',{'setup_token':'whatever'}).status_code,409)
        self.assertEqual(self.client.get('/').status_code,200)
        self.assertEqual(self.post('/api/auth/logout').status_code,200)
        self.assertIsNone(self.client.get('/api/auth/session').get_json()['user'])
        self.csrf=self.client.get('/api/auth/session').get_json()['csrf']
        self.assertEqual(self.post('/api/auth/login',{'username':'Moosketair','password':'superlongtestpassword!'}).status_code,200)

    def test_csrf_prevents_mutations(self):
        self.setup_admin()
        resp=self.client.post('/api/favorites',json={'name':'test','content_id':HASH})
        self.assertEqual(resp.status_code,403)

    def test_user_isolation_and_admin_permissions(self):
        self.setup_admin()
        r=self.post('/api/favorites',{'name':'Mes infos','content_id':HASH,'group_name':'TV','notes':''})
        self.assertEqual(r.status_code,201,r.get_data(as_text=True))
        fav=r.get_json()['id']
        self.assertEqual(len(self.client.get('/api/favorites').get_json()['favorites']),1)
        user=self.post('/api/users',{'username':'someone','password':'not a short password','role':'user'})
        self.assertEqual(user.status_code,201,user.get_data(as_text=True))
        other=self.app.test_client()
        othercsrf=other.get('/api/auth/session').get_json()['csrf']
        login=other.post('/api/auth/login',json={'username':'someone','password':'not a short password'},headers={'X-CSRF-Token':othercsrf})
        self.assertEqual(login.status_code,200)
        othercsrf=login.get_json()['csrf']
        self.assertEqual(other.get('/api/favorites').get_json()['favorites'],[])
        self.assertEqual(self.post('/api/favorites/'+str(fav),client=other,token=othercsrf,method='DELETE').status_code,404)
        self.assertEqual(other.get('/api/users').status_code,403)
        self.assertEqual(self.post('/api/users',{'username':'another','password':'verylongpasswordyes'},client=other,token=othercsrf).status_code,403)
        self.assertEqual(self.post('/api/favorites/'+str(fav),{'name':'Mon nom','content_id':HASH,'group_name':'Modifié','notes':'ok'},method='PATCH').status_code,200)
        self.assertEqual(self.client.get('/api/favorites').get_json()['favorites'][0]['name'],'Mon nom')
        self.assertEqual(self.post('/api/favorites/'+str(fav),method='DELETE').status_code,200)

    def test_vpn_fail_closed_without_ffmpeg(self):
        self.setup_admin()
        with patch('app.requests.get',side_effect=Exception('should-be-requests-exception')):
            pass
        with patch('app.requests.get') as get:
            import requests
            get.side_effect=requests.ConnectionError()
            resp=self.post('/api/streams/start',{'content_id':HASH})
            self.assertEqual(resp.status_code,503)
            self.assertIn('VPN',resp.get_json()['error'])
            self.assertFalse(self.app.extensions['stream_manager'].streams)

    def test_monitor_uses_real_documented_api(self):
        self.setup_admin()
        class FakeResponse:
            def __init__(self,data):self.data=data
            def raise_for_status(self):pass
            def json(self):return self.data
        def fetch(url,**kwargs):
            if url.endswith('/v1/vpn/status'):return FakeResponse({'status':'running'})
            if url.endswith('/v1/publicip/ip'):return FakeResponse({'public_ip':'192.0.2.123'})
            if url.endswith('/webui/api/service'):return FakeResponse({'result':{'version':'3.2.17'},'error':None})
            raise AssertionError(url)
        with patch('app.requests.get',side_effect=fetch):
            result=self.client.get('/api/monitor')
        self.assertEqual(result.status_code,200)
        data=result.get_json()
        self.assertTrue(data['vpn']['connected'])
        self.assertEqual(data['vpn']['public_ip'],'192.0.2.123')
        self.assertEqual(data['engine']['version'],'3.2.17')

    def test_external_search_mock(self):
        self.setup_admin()
        searcher=self.app.extensions['playlist_search']
        with patch.object(searcher,'search',return_value=([{'name':'Radio TV','category':'Divers','content_id':HASH,'source':'search-ace.stream'}],1,None)):
            data=self.client.get('/api/search?q=radio')
        self.assertEqual(data.status_code,200)
        self.assertEqual(data.get_json()['results'][0]['name'],'Radio TV')
        self.assertEqual(self.client.get('/api/search?q=a').status_code,400)


    def test_password_minimum_ten_on_setup(self):
        payload = {'setup_token': 'testonly-_secret-key-of-32-characters-abcd',
                   'username': 'minimum10', 'password': 'abcdefghi'}
        self.assertEqual(self.post('/api/auth/setup', payload).status_code, 400)
        payload['password'] = 'abcdefghij'
        accepted = self.post('/api/auth/setup', payload)
        self.assertEqual(accepted.status_code, 201, accepted.get_data(as_text=True))
        self.assertEqual(accepted.get_json()['user']['username'], 'minimum10')

    def test_password_minimum_ten_for_new_users_and_password_changes(self):
        self.setup_admin()
        rejected = self.post('/api/users', {'username':'tester10', 'password':'abcdefghi'})
        self.assertEqual(rejected.status_code, 400)
        created = self.post('/api/users', {'username':'tester10', 'password':'abcdefghij'})
        self.assertEqual(created.status_code, 201, created.get_data(as_text=True))
        rejected_change = self.post('/api/auth/password', {
            'old_password':'superlongtestpassword!', 'new_password':'abcdefghi'})
        self.assertEqual(rejected_change.status_code, 400)
        changed = self.post('/api/auth/password', {
            'old_password':'superlongtestpassword!', 'new_password':'0123456789'})
        self.assertEqual(changed.status_code, 200, changed.get_data(as_text=True))
        self.assertEqual(self.post('/api/auth/logout').status_code, 200)
        self.csrf = self.client.get('/api/auth/session').get_json()['csrf']
        login = self.post('/api/auth/login', {'username':'Moosketair', 'password':'0123456789'})
        self.assertEqual(login.status_code, 200, login.get_data(as_text=True))

    def test_dashboard_reorder_player_search_favorites_then_status_cards(self):
        html = self.client.get('/').get_data(as_text=True)
        elements = ('class="panel player-panel"', 'class="panel search-panel"',
                    'class="panel favorites-panel"', 'class="dashboard-details"',
                    'class="metrics"')
        indexes = [html.index(element) for element in elements]
        self.assertEqual(indexes, sorted(indexes))
        self.assertIn('minlength="10"', html)
        self.assertNotIn('minlength="12"', html)

    def test_player_spinner_android_landscape_and_site_icon(self):
        response = self.client.get('/')
        self.assertEqual(response.status_code, 200)
        html = response.get_data(as_text=True)
        self.assertIn('id="videoFrame"', html)
        self.assertIn('id="videoLoading"', html)
        self.assertIn('id="videoLoadingLabel"', html)
        self.assertIn('id="fullscreenBtn"', html)
        self.assertIn('href="/static/favicon.svg"', html)
        self.assertIn('href="/static/site.webmanifest"', html)

        js = self.client.get('/static/app.js').get_data(as_text=True)
        self.assertIn("addEventListener('playing'", js)
        self.assertIn("addEventListener('waiting'", js)
        self.assertIn('function syncAndroidLandscape(', js)
        self.assertIn("requestFullscreen()", js)
        self.assertIn('android-landscape-video', js)

        css = self.client.get('/static/app.css').get_data(as_text=True)
        self.assertIn('.video-loading-spinner', css)
        self.assertIn('body.android-landscape-video .video-frame', css)

        from xml.etree import ElementTree
        icon = self.client.get('/static/favicon.svg')
        self.assertEqual(icon.status_code, 200)
        root = ElementTree.fromstring(icon.get_data(as_text=True))
        self.assertTrue(root.tag.endswith('svg'))
        manifest = self.client.get('/static/site.webmanifest')
        self.assertEqual(manifest.status_code, 200)
        self.assertEqual(manifest.get_json()['short_name'], 'AceStream')


    def test_search_sources_engine_playlist_and_merged_results(self):
        self.setup_admin()
        import requests
        from unittest.mock import patch
        class VPNResponse:
            def raise_for_status(self): pass
            def json(self): return {"status": "running"}
        # Stub the VPN check: the engine's public network is never accessed.
        engine_item = {"name": "Chaîne API", "category": "tv", "content_id": HASH,
                       "source": "API Ace Stream", "status": 2}
        playlist_item = {"name": "Chaîne playlist", "category": "tv", "content_id": HASH,
                         "source": "search-ace.stream"}
        with (patch('app.requests.get', return_value=VPNResponse()),
              patch.object(self.app.extensions['engine_search'], 'search',
                           return_value=([engine_item], 1, None)) as engine,
              patch.object(self.app.extensions['playlist_search'], 'search',
                           return_value=([playlist_item], 1, None)) as playlist):
            merged = self.client.get('/api/search?q=chaine&source=all')
            self.assertEqual(merged.status_code, 200, merged.get_data(as_text=True))
            self.assertEqual(len(merged.get_json()['results']), 1)
            self.assertEqual(merged.get_json()['results'][0]['source'], 'API Ace Stream')
            self.assertEqual(merged.get_json()['results'][0]['status'], 2)
            self.assertEqual(merged.get_json()['sources'], ['API Ace Stream','search-ace.stream'])
            eng = self.client.get('/api/search?q=chaine&source=engine')
            self.assertEqual(eng.status_code, 200)
            self.assertEqual(eng.get_json()['sources'], ['API Ace Stream'])
            full = self.client.get('/api/search?q=chaine&source=playlist')
            self.assertEqual(full.status_code, 200)
            self.assertEqual(full.get_json()['sources'], ['search-ace.stream'])
            self.assertEqual(self.client.get('/api/search?q=chaine&source=evil').status_code, 400)
            self.assertEqual(engine.call_count, 2)
            self.assertEqual(playlist.call_count, 2)

    def test_search_engine_unavailable_fails_gracefully(self):
        self.setup_admin()
        import requests
        with (patch('app.requests.get', side_effect=requests.ConnectionError()),
              patch.object(self.app.extensions['playlist_search'], 'search',
                           return_value=([], 0, None))):
            result = self.client.get('/api/search?q=test&source=all')
            self.assertEqual(result.status_code, 200)
            self.assertIn('VPN indisponible', result.get_json()['warning'])
            only_engine = self.client.get('/api/search?q=test&source=engine')
            self.assertEqual(only_engine.status_code, 503)

    def test_pip_button_and_source_selectors(self):
        html = self.client.get('/').get_data(as_text=True)
        for id_ in ('pipBtn', 'searchSourceSelect', 'searchSourceSelect2'):
            self.assertIn(f'id="{id_}"', html)
        js = self.client.get('/static/app.js').get_data(as_text=True)
        self.assertIn("setActionHandler('enterpictureinpicture'", js)
        self.assertIn("addEventListener('visibilitychange'", js)
        self.assertIn("requestPictureInPicture()", js)
        css = self.client.get('/static/app.css').get_data(as_text=True)
        self.assertIn('.pip-action', css)

if __name__ == '__main__':unittest.main()
