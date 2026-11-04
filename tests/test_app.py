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

if __name__ == '__main__':unittest.main()
