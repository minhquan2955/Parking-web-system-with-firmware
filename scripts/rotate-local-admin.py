"""Rotate the generated local demo admin password without printing credentials."""
import http.cookiejar
import json
import secrets
import urllib.request
from pathlib import Path
root=Path(__file__).resolve().parents[1]
file=root/'.env'
lines=file.read_text().splitlines()
env=dict(line.split('=',1) for line in lines if line and not line.startswith('#') and '=' in line)
opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def call(path,body=None,method=None,headers=None):
    req=urllib.request.Request('http://127.0.0.1:8080/api/v1'+path,data=None if body is None else json.dumps(body).encode(),method=method,headers={'Content-Type':'application/json',**(headers or {})})
    with opener.open(req,timeout=15) as res:
        data=res.read()
        return json.loads(data) if data else None
def mutate(path,data,method='POST'):
    token=call('/auth/csrf')
    return call(path,data,method,{token['headerName']:token['token']})
mutate('/auth/login',{'username':env['ADMIN_USERNAME'],'password':env['ADMIN_PASSWORD']})
replacement=secrets.token_urlsafe(30)
mutate('/me/password',{'currentPassword':env['ADMIN_PASSWORD'],'newPassword':replacement},'PUT')
file.write_text('\n'.join('ADMIN_PASSWORD='+replacement if line.startswith('ADMIN_PASSWORD=') else line for line in lines)+'\n')
print('Local admin password rotated; all prior admin sessions invalidated. Updated .env without displaying credentials.')
