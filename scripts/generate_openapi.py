"""Write OpenAPI in JSON syntax (a valid YAML subset), keeping every endpoint explicit."""
import json
import re
from pathlib import Path
root=Path(__file__).resolve().parents[1]
schemas={}
def ref(name): return {'$ref':f'#/components/schemas/{name}'}
def obj(name, fields, optional=()):
 props={}
 for key,typ in fields.items():
  nullable=typ.endswith('?');typ=typ.rstrip('?')
  if typ.startswith('['): value={'type':'array','items':ref(typ[1:-1])}
  elif '|' in typ:value={'type':'string','enum':typ.split('|')}
  elif typ in ('string','boolean','integer','number','object'):value={'type':typ}
  elif typ=='uuid':value={'type':'string','format':'uuid'}
  elif typ=='time':value={'type':'string','format':'date-time'}
  else:value=ref(typ)
  if nullable:
   if '$ref' in value:value={'allOf':[value]}
   value['nullable']=True
  props[key]=value
 schemas[name]={'type':'object','additionalProperties':False,'required':[k for k in fields if k not in optional],'properties':props}
def fields(s):return dict(v.split(':',1) for v in s.split())
obj('FieldError',fields('field:string message:string'))
obj('Error',fields('code:string message:string fieldErrors:[FieldError] traceId:string'))
schemas['Error']['properties']['orderId']={'type':'string','format':'uuid','description':'Existing order for ORDER_IN_PROGRESS; only returned within authorized ownership.'}
error_codes={'AUTH_REQUIRED','CSRF_INVALID','FORBIDDEN','DATABASE_UNAVAILABLE','VALIDATION_ERROR','NOT_FOUND','LOGIN_RATE_LIMITED','REGISTRATION_RATE_LIMITED'}
for source in (root/'backend/src/main/java').rglob('*.java'):
 text=source.read_text(encoding='utf-8')
 error_codes.update(re.findall(r'new Problem\(\s*\d+,\s*"([A-Z_]+)"',text))
 error_codes.update(re.findall(r'Problem\.conflict\(\s*"([A-Z_]+)"',text))
schemas['Error']['properties']['code']['enum']=sorted(error_codes)
obj('UserView',fields('id:uuid username:string fullName:string phone:string? role:USER|ADMIN status:ACTIVE|DISABLED createdAt:time'))
obj('CardView',fields('id:uuid uid:string ownerId:uuid ownerName:string status:ENABLED|BLOCKED effectiveStatus:BLOCKED|NOT_ACTIVATED|EXPIRED|ACTIVE expiresAt:time? hasOpenSession:boolean createdAt:time'))
obj('PackageView',fields('id:uuid code:string name:string durationDays:integer priceVnd:number currency:string'))
obj('OrderView',fields('id:uuid cardId:uuid packageCode:string durationDays:integer amountVnd:number currency:string paymentMode:mock|payos status:CREATING|PENDING|PAID|EXPIRED|FAILED|REVIEW createdAt:time expiresAt:time serverTime:time qrPayload:string? checkoutUrl:string? paidAt:time? renewalAppliedAt:time? oldExpiresAt:time? newExpiresAt:time?'))
obj('SessionView',fields('id:uuid cardId:uuid cardUid:string ownerId:uuid ownerName:string status:OPEN|CLOSED|VOIDED entryAt:time exitAt:time? closureType:SCAN|ADMIN?'))
obj('SlotView',fields('slotId:S1|S2|S3 state:FREE|OCCUPIED|UNKNOWN reportedState:FREE|OCCUPIED|UNKNOWN reportedAt:time?'))
obj('OccupancyView',fields('capacity:integer occupiedCount:integer freeCount:integer unknownCount:integer openSessionCount:integer admissionAvailable:integer? stale:boolean updatedAt:time? serverTime:time slots:[SlotView]'))
obj('DeviceSummary',fields('code:string status:ONLINE|OFFLINE|NEVER_SEEN lastSeenAt:time? serverTime:time'))
obj('DeviceView',fields('code:string status:ONLINE|OFFLINE|NEVER_SEEN lastSeenAt:time? serverTime:time firmwareVersion:string? currentBootId:uuid? uptimeMs:integer?'))
obj('AccessEventView',fields('id:uuid eventId:uuid deviceCode:string gate:IN|OUT cardUid:string decision:ALLOW|DENY reason:string sessionId:uuid? receivedAt:time'))
obj('PaymentReceiptView',fields('id:uuid provider:string transactionRef:string? orderId:uuid? providerOrderCode:integer? amountVnd:number? currency:string? paidAt:time? processingStatus:RECEIVED|APPLIED|DUPLICATE|REVIEW|UNMATCHED|EXCESS_PAYMENT_REVIEW receivedAt:time'))
obj('AuditView',fields('id:uuid actorType:string actorId:uuid? action:string entityType:string entityId:uuid beforeJson:string afterJson:string reason:string? createdAt:time'))
obj('CsrfView',fields('token:string headerName:string'))
obj('RegisterRequest',fields('username:string password:string fullName:string phone:string?'),('phone',))
obj('LoginRequest',fields('username:string password:string'))
obj('ProfileRequest',fields('fullName:string phone:string?'),('phone',))
obj('PasswordRequest',fields('currentPassword:string newPassword:string'))
obj('CreateUserRequest',fields('username:string initialPassword:string fullName:string phone:string?'),('phone',))
obj('UpdateUserRequest',fields('fullName:string phone:string? status:ACTIVE|DISABLED'),('phone','status'))
obj('CreateCardRequest',fields('uid:string ownerId:uuid'))
obj('CardStatusRequest',fields('status:ENABLED|BLOCKED reason:string'))
obj('CreateOrderRequest',fields('packageId:uuid'))
obj('ReconcileRequest',fields('acceptLatePayment:boolean reason:string?'),('reason',))
obj('CorrectionRequest',fields('action:VOID_ENTRY|CLOSE|REOPEN reason:string'))
obj('SimulationRequest',fields('scenario:SUCCESS|AMOUNT_MISMATCH|LATE|DUPLICATE'))
obj('SlotReport',fields('slotId:S1|S2|S3 state:FREE|OCCUPIED|UNKNOWN'))
obj('HeartbeatRequest',fields('bootId:uuid seq:integer uptimeMs:integer firmwareVersion:string slots:[SlotReport]'))
schemas['HeartbeatRequest']['properties']['slots'].update(minItems=3,maxItems=3)
obj('HeartbeatResponse',fields('accepted:boolean serverTime:time'))
obj('ScanRequest',fields('eventId:uuid bootId:uuid gate:IN|OUT cardUid:string'))
obj('ScanResponse',fields('eventId:uuid gate:IN|OUT decision:ALLOW|DENY reason:ENTRY_ALLOWED|EXIT_ALLOWED|UNKNOWN_CARD|CARD_BLOCKED|USER_DISABLED|CARD_EXPIRED|ALREADY_INSIDE|OCCUPANCY_UNKNOWN|PARKING_FULL|NO_ACTIVE_SESSION sessionId:uuid? command:OPEN|NONE openDurationMs:integer serverTime:time validUntil:time?'))
obj('WebhookData',fields('orderCode:integer amount:number paymentLinkId:string reference:string currency:string transactionDateTime:string code:string desc:string description:string accountNumber:string counterAccountBankId:string counterAccountBankName:string counterAccountName:string counterAccountNumber:string virtualAccountName:string virtualAccountNumber:string'),('counterAccountBankId','counterAccountBankName','counterAccountName','counterAccountNumber','virtualAccountName','virtualAccountNumber'))
schemas['WebhookData']['additionalProperties']=True
obj('PayosWebhook',fields('code:string desc:string success:boolean data:WebhookData signature:string'))
obj('Ack',fields('accepted:boolean'))
for name in ['UserView','CardView','OrderView','SessionView','AccessEventView','PaymentReceiptView','AuditView']:
 obj(name+'Page',{'items':'['+name+']',**fields('page:integer size:integer totalElements:integer totalPages:integer')})
for name in ('RegisterRequest','CreateUserRequest'):
 schemas[name]['properties']['username'].update(minLength=3,maxLength=50,description='Trim and lowercase, then ASCII [a-z0-9._-].')
 schemas[name]['properties']['fullName'].update(minLength=1,maxLength=100)
 schemas[name]['properties']['phone']['maxLength']=20
for name in ('RegisterRequest','LoginRequest','PasswordRequest','CreateUserRequest'):
 for key in schemas[name]['properties']:
  if 'password' in key.lower():schemas[name]['properties'][key].update(writeOnly=True,description='10–72 UTF-8 bytes for a new password; never trim.')
for name in ('CorrectionRequest','CardStatusRequest'):
 schemas[name]['properties']['reason'].update(minLength=10,maxLength=500)
paths={}
def route(method,path,role,response=None,request=None,status=200,paged=False,filters=()):
 security=[] if role=='PUBLIC' else [{'DeviceId':[],'DeviceKey':[]}] if role=='DEVICE' else [] if role=='WEBHOOK' else [{'Session':[]}]
 operation={'operationId':method+'_'+path.replace('/','_').replace('{','').replace('}',''),'tags':[role],'summary':f'{method.upper()} {path}','security':security,'responses':{},'parameters':[]}
 if '{id}' in path:operation['parameters'].append({'in':'path','name':'id','required':True,'schema':{'type':'string','format':'uuid'}})
 if method!='get' and role not in ('DEVICE','WEBHOOK'):operation['parameters'].append({'in':'header','name':'X-CSRF-TOKEN','required':True,'schema':{'type':'string'},'description':'Obtain fresh context after login/logout via /auth/csrf.'})
 if paged:
  operation['parameters'] += [{'in':'query','name':k,'schema':{'type':'integer','default':v,'minimum':0 if k=='page' else 1,**({'maximum':100} if k=='size' else {})}} for k,v in [('page',0),('size',20)]]
  filters=(*filters,'sort')
 for name in filters:operation['parameters'].append({'in':'query','name':name,'schema':{'type':'boolean' if name=='applied' else 'string',**({'format':'date-time'} if name in ('from','to') else {}),**({'format':'uuid'} if name.endswith('Id') else {}),**({'enum':['asc','desc'],'default':'desc'} if name=='sort' else {})}})
 if request:operation['requestBody']={'required':True,'content':{'application/json':{'schema':ref(request)}}}
 success={'description':'Success'}
 if response:success['content']={'application/json':{'schema':{'type':'array','items':ref(response[1:])} if response.startswith('[') else ref(response)}}
 operation['responses'][str(status)]=success
 for code in [400,401,403,404,409,413,429,503]:operation['responses'][str(code)]={'description':{400:'Validation / signature error',401:'Authentication required',403:'Role / CSRF denied',404:'Missing or not owned',409:'Business or idempotency conflict',413:'Body too large',429:'Rate limited',503:'Retryable infrastructure failure'}[code],'content':{'application/json':{'schema':ref('Error')}}}
 operation['responses']['429']['headers']={'Retry-After':{'schema':{'type':'integer'},'description':'Registration/login rate limit retry delay, seconds.'}}
 paths.setdefault(path,{})[method]=operation
route('get','/auth/csrf','PUBLIC','CsrfView')
route('post','/auth/register','PUBLIC','UserView','RegisterRequest',201)
route('post','/auth/login','PUBLIC','UserView','LoginRequest')
route('post','/auth/logout','USER',status=204)
route('get','/me','USER','UserView');route('patch','/me','USER','UserView','ProfileRequest');route('put','/me/password','USER',request='PasswordRequest',status=204)
route('get','/admin/users','ADMIN','UserViewPage',paged=True,filters=('search','status'))
route('post','/admin/users','ADMIN','UserView','CreateUserRequest',201);route('patch','/admin/users/{id}','ADMIN','UserView','UpdateUserRequest')
route('get','/cards','USER','CardViewPage',paged=True,filters=('ownerId','search','effectiveStatus'));route('get','/cards/{id}','OWNER','CardView')
route('post','/admin/cards','ADMIN','CardView','CreateCardRequest',201);route('patch','/admin/cards/{id}/status','ADMIN','CardView','CardStatusRequest')
route('get','/renewal-packages','USER','[PackageView')
route('post','/cards/{id}/renewal-orders','OWNER','OrderView','CreateOrderRequest',201)
op=paths['/cards/{id}/renewal-orders']['post'];op['parameters'].append({'in':'header','name':'Idempotency-Key','required':True,'schema':{'type':'string','format':'uuid'}})
for status in ('200','202'):op['responses'][status]=op['responses']['201']
route('get','/renewal-orders','USER','OrderViewPage',paged=True,filters=('cardId','status','applied','from','to'));route('get','/renewal-orders/{id}','OWNER','OrderView');route('post','/renewal-orders/{id}/reconcile','OWNER','OrderView','ReconcileRequest')
route('get','/parking/sessions','USER','SessionViewPage',paged=True,filters=('cardId','status','from','to'));route('get','/parking/occupancy','USER','OccupancyView')
route('get','/devices/summary','USER','DeviceSummary');route('get','/admin/devices','ADMIN','[DeviceView')
route('get','/admin/access-events','ADMIN','AccessEventViewPage',paged=True,filters=('uid','gate','decision','from','to'))
route('post','/admin/parking/sessions/{id}/corrections','ADMIN','SessionView','CorrectionRequest')
route('get','/admin/audit-logs','ADMIN','AuditViewPage',paged=True,filters=('entityType','entityId','from','to'))
route('get','/admin/payment-receipts','ADMIN','PaymentReceiptViewPage',paged=True,filters=('orderId','processingStatus','from','to'))
route('post','/device/heartbeats','DEVICE','HeartbeatResponse','HeartbeatRequest');route('post','/device/access-events','DEVICE','ScanResponse','ScanRequest')
route('post','/payments/webhooks/payos','WEBHOOK','Ack','PayosWebhook')
paths['/payments/webhooks/payos']['post']['description']='payOS signed data object required. Session/CSRF cannot authorize payment. Route rejects in mock mode.'
route('post','/admin/dev/renewal-orders/{id}/simulate-payment','ADMIN','OrderView','SimulationRequest')
paths['/admin/dev/renewal-orders/{id}/simulate-payment']['post']['description']='Exists ONLY in dev profile with PAYMENT_MODE=mock.'
api={'openapi':'3.0.3','info':{'title':'MAIN Smart Parking','version':'1.0.0','description':'SRS 2.3. ADMIN includes USER privileges; OWNER means resource ownership or ADMIN. Business ALLOW/DENY both return 200. Timestamps UTC; history ranges [from,to).'},'servers':[{'url':'/api/v1'}],'paths':paths,'components':{'schemas':schemas,'securitySchemes':{'Session':{'type':'apiKey','in':'cookie','name':'JSESSIONID'},'DeviceId':{'type':'apiKey','in':'header','name':'X-Device-Id'},'DeviceKey':{'type':'apiKey','in':'header','name':'X-Device-Key'}}}}
(root/'docs/openapi.yaml').write_text(json.dumps(api,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
