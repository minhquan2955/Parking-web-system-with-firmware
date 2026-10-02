"""Local demo performance check. Creates a dedicated mock-paid card; never charges money."""
import http.cookiejar
import json
import math
import statistics
import time
import urllib.request
import uuid
from pathlib import Path

root=Path(__file__).resolve().parents[1]
env=dict(line.split('=',1) for line in (root/'.env').read_text().splitlines() if line and not line.startswith('#') and '=' in line)
if env.get('PAYMENT_MODE')!='mock':raise SystemExit('Benchmark requires PAYMENT_MODE=mock')
base='http://127.0.0.1:3000/api/v1'
opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def call(path,body=None,headers=None):
    request=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json',**(headers or {})})
    with opener.open(request,timeout=15) as response:return json.load(response)
def web(path,body,headers=None):
    csrf=call('/auth/csrf')
    return call(path,body,{csrf['headerName']:csrf['token'],**(headers or {})})
web('/auth/login',{'username':env['ADMIN_USERNAME'],'password':env['ADMIN_PASSWORD']})
owner=web('/admin/users',{'username':'bench'+uuid.uuid4().hex[:12],'initialPassword':uuid.uuid4().hex,'fullName':'Kiểm thử hiệu năng'})
card=web('/admin/cards',{'ownerId':owner['id'],'uid':uuid.uuid4().hex[:8]})
package=call('/renewal-packages')[0]
order=web('/cards/'+card['id']+'/renewal-orders',{'packageId':package['id']},{'Idempotency-Key':str(uuid.uuid4())})
started=time.perf_counter()
web('/admin/dev/renewal-orders/'+order['id']+'/simulate-payment',{'scenario':'SUCCESS'})
payment_ms=(time.perf_counter()-started)*1000
boot=str(uuid.uuid4());headers={'X-Device-Id':'ESP32-01','X-Device-Key':env['DEVICE_KEY']}
latencies=[]
for i in range(100):
    if i%10==0:
        call('/device/heartbeats',{'bootId':boot,'seq':i+1,'uptimeMs':i*100,'firmwareVersion':'benchmark-1.0','slots':[{'slotId':s,'state':'FREE'} for s in ['S1','S2','S3']]},headers)
    started=time.perf_counter()
    result=call('/device/access-events',{'eventId':str(uuid.uuid4()),'bootId':boot,'gate':'IN' if i%2==0 else 'OUT','cardUid':card['uid']},headers)
    latencies.append((time.perf_counter()-started)*1000)
    assert result['decision']=='ALLOW',result
report={'environment':'Windows / Java 21 / PostgreSQL embedded / Next production / loopback HTTP, not physical LAN','scanCount':100,'p50Ms':round(statistics.median(latencies),2),'p95Ms':round(sorted(latencies)[math.ceil(len(latencies)*.95)-1],2),'maxMs':round(max(latencies),2),'mockPaymentRoundTripMs':round(payment_ms,2),'meetsLocalScanTarget':sum(t<=1000 for t in latencies)>=95,'meetsLocalPaymentTarget':payment_ms<=2000}
(root/'docs/benchmark.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
