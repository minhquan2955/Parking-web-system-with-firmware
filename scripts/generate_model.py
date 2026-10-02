"""Generate explicit scalar JPA mappings and initial PostgreSQL schema from this checked-in model.
Run only when deliberately changing the initial schema before deployment.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODELS = {
 'User': ('users', 'username:str passwordHash:str fullName:str phone:str? role:str status:str'),
 'Card': ('cards', 'uid:str ownerId:uuid status:str expiresAt:time?'),
 'RenewalPackage': ('renewal_packages', 'code:str name:str durationDays:int priceVnd:money enabled:bool'),
 'RenewalOrder': ('renewal_orders', 'cardId:uuid ownerIdSnapshot:uuid createdBy:uuid packageId:uuid packageCodeSnapshot:str durationDaysSnapshot:int amountVnd:money currency:str provider:str providerOrderCode:long providerPaymentId:str? qrPayload:text? checkoutUrl:text? status:str expiresAt:time paidAt:time? idempotencyKey:uuid requestHash:str nextReconcileAt:time?'),
 'PaymentReceipt': ('payment_receipts', 'provider:str transactionRef:str? deliveryHash:str orderId:uuid? providerOrderCode:long? amountVnd:money? currency:str? paidAt:time? verificationStatus:str processingStatus:str sanitizedPayload:text receivedAt:time'),
 'CardRenewal': ('card_renewals', 'orderId:uuid cardId:uuid oldExpiresAt:time? newExpiresAt:time appliedAt:time'),
 'ParkingLot': ('parking_lots', 'code:str capacity:int'),
 'ParkingSession': ('parking_sessions', 'lotId:uuid cardId:uuid ownerIdSnapshot:uuid status:str entryAt:time exitAt:time? closureType:str?'),
 'AccessEvent': ('access_events', 'deviceId:uuid eventId:uuid bootId:uuid gate:str cardUid:str cardId:uuid? sessionId:uuid? decision:str reason:str requestHash:str responseJson:text receivedAt:time'),
 'Device': ('devices', 'code:str apiKeyHash:str enabled:bool currentBootId:uuid? lastSeenAt:time? firmwareVersion:str? uptimeMs:long?'),
 'DeviceBoot': ('device_boots', 'deviceId:uuid bootId:uuid lastSeq:long firstSeenAt:time'),
 'ParkingSlot': ('parking_slots', 'lotId:uuid code:str deviceId:uuid reportedState:str reportedAt:time?'),
 'AuditLog': ('audit_logs', 'actorType:str actorId:uuid? action:str entityType:str entityId:uuid beforeJson:text afterJson:text reason:text?'),
}
def snake(s):
 import re
 return re.sub(r'(?<!^)(?=[A-Z])', '_', s).lower()
types = {'str':('String','varchar(255)'), 'text':('String','text'), 'uuid':('UUID','uuid'), 'time':('Instant','timestamptz'), 'int':('Integer','integer'), 'long':('Long','bigint'), 'money':('BigDecimal','numeric(12,0)'), 'bool':('Boolean','boolean')}
folder = ROOT/'backend/src/main/java/vn/parking/model'
folder.mkdir(parents=True, exist_ok=True)
(folder/'BaseEntity.java').write_text('''package vn.parking.model;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@MappedSuperclass
public abstract class BaseEntity {
 @Id public UUID id = UUID.randomUUID();
 @Column(nullable=false) public Instant createdAt = Instant.now();
 @Column(nullable=false) public Instant updatedAt = Instant.now();
 @PreUpdate void touch() { updatedAt = Instant.now(); }
}
''', encoding='utf-8')
sql = ['-- SRS 2.3: never edit this migration after it has run.', 'CREATE SEQUENCE provider_order_code_seq START 100000;']
for cls,(table,fields) in MODELS.items():
 java = ['package vn.parking.model;', 'import jakarta.persistence.*;', 'import java.time.Instant;', 'import java.util.UUID;', 'import java.math.BigDecimal;', f'@Entity @Table(name="{table}")', f'public class {cls} extends BaseEntity {{']
 cols = ['id uuid PRIMARY KEY', 'created_at timestamptz NOT NULL DEFAULT now()', 'updated_at timestamptz NOT NULL DEFAULT now()']
 for spec in fields.split():
  field, typ = spec.split(':'); nullable=typ.endswith('?'); typ=typ.rstrip('?'); jt,st=types[typ]
  extra = ', columnDefinition="text"' if typ=='text' else ', precision=12, scale=0' if typ=='money' else ''
  java.append(f' @Column(name="{snake(field)}", nullable={str(nullable).lower()}{extra}) public {jt} {field};')
  cols.append(f'{snake(field)} {st}' + ('' if nullable else ' NOT NULL'))
 java.append('}')
 (folder/f'{cls}.java').write_text('\n'.join(java)+'\n', encoding='utf-8')
 sql.append(f'CREATE TABLE {table} (\n  '+',\n  '.join(cols)+'\n);')
for table,col,target in [('cards','owner_id','users'),('renewal_orders','card_id','cards'),('renewal_orders','owner_id_snapshot','users'),('renewal_orders','created_by','users'),('renewal_orders','package_id','renewal_packages'),('payment_receipts','order_id','renewal_orders'),('card_renewals','card_id','cards'),('parking_sessions','lot_id','parking_lots'),('parking_sessions','card_id','cards'),('parking_sessions','owner_id_snapshot','users'),('access_events','device_id','devices'),('access_events','card_id','cards'),('access_events','session_id','parking_sessions'),('device_boots','device_id','devices'),('parking_slots','lot_id','parking_lots'),('parking_slots','device_id','devices')]:
 sql.append(f'ALTER TABLE {table} ADD FOREIGN KEY ({col}) REFERENCES {target}(id) ON DELETE RESTRICT;')
sql.append('''
ALTER TABLE users ADD UNIQUE(username), ADD CHECK(role IN ('USER','ADMIN')), ADD CHECK(status IN ('ACTIVE','DISABLED'));
ALTER TABLE cards ADD UNIQUE(uid), ADD CHECK(status IN ('ENABLED','BLOCKED'));
ALTER TABLE renewal_packages ADD UNIQUE(code), ADD CHECK(duration_days > 0 AND price_vnd > 0);
ALTER TABLE renewal_orders ADD UNIQUE(provider,provider_order_code), ADD UNIQUE(provider,provider_payment_id), ADD UNIQUE(id,card_id), ADD UNIQUE(created_by,idempotency_key), ADD CHECK(amount_vnd > 0 AND duration_days_snapshot > 0 AND currency='VND'), ADD CHECK(status IN ('CREATING','PENDING','PAID','FAILED','EXPIRED','REVIEW')), ADD CHECK(status <> 'PAID' OR paid_at IS NOT NULL);
ALTER TABLE payment_receipts ADD UNIQUE(provider,transaction_ref), ADD UNIQUE(provider,delivery_hash), ADD CHECK(verification_status='VERIFIED'), ADD CHECK(processing_status IN ('RECEIVED','APPLIED','DUPLICATE','REVIEW','UNMATCHED','EXCESS_PAYMENT_REVIEW'));
ALTER TABLE card_renewals ADD UNIQUE(order_id), ADD FOREIGN KEY(order_id,card_id) REFERENCES renewal_orders(id,card_id) ON DELETE RESTRICT, ADD CHECK(new_expires_at > applied_at AND (old_expires_at IS NULL OR new_expires_at > old_expires_at));
ALTER TABLE parking_lots ADD UNIQUE(code), ADD CHECK(capacity=3);
ALTER TABLE devices ADD UNIQUE(code);
ALTER TABLE device_boots ADD UNIQUE(device_id,boot_id), ADD CHECK(last_seq >= 0);
ALTER TABLE access_events ADD UNIQUE(device_id,event_id), ADD CHECK(gate IN ('IN','OUT')), ADD CHECK(decision IN ('ALLOW','DENY'));
ALTER TABLE parking_slots ADD UNIQUE(lot_id,code), ADD CHECK(code IN ('S1','S2','S3')), ADD CHECK(reported_state IN ('FREE','OCCUPIED','UNKNOWN'));
ALTER TABLE parking_sessions ADD CHECK((status='OPEN' AND exit_at IS NULL AND closure_type IS NULL) OR (status='CLOSED' AND exit_at >= entry_at AND closure_type IN ('SCAN','ADMIN')) OR (status='VOIDED' AND exit_at IS NULL AND closure_type='ADMIN'));
CREATE UNIQUE INDEX uq_enabled_card_owner ON cards(owner_id) WHERE status='ENABLED';
CREATE UNIQUE INDEX uq_open_session_card ON parking_sessions(card_id) WHERE status='OPEN';
CREATE UNIQUE INDEX uq_open_session_owner ON parking_sessions(owner_id_snapshot) WHERE status='OPEN';
CREATE UNIQUE INDEX uq_live_order_card ON renewal_orders(card_id) WHERE status IN ('CREATING','PENDING');
CREATE INDEX ix_session_owner_time ON parking_sessions(owner_id_snapshot,entry_at DESC);
CREATE INDEX ix_session_card_time ON parking_sessions(card_id,entry_at DESC);
CREATE INDEX ix_events_time ON access_events(received_at DESC);
CREATE INDEX ix_orders_reconcile ON renewal_orders(status,next_reconcile_at);
INSERT INTO parking_lots(id,code,capacity) VALUES ('00000000-0000-0000-0000-000000000001','MAIN',3);
-- Provision the device credential hash from DEVICE_KEY at startup. Empty hash cannot authenticate.
INSERT INTO devices(id,code,api_key_hash,enabled) VALUES ('00000000-0000-0000-0000-000000000002','ESP32-01','',true);
INSERT INTO parking_slots(id,lot_id,code,device_id,reported_state)
SELECT ('00000000-0000-0000-0000-00000000000'||n)::uuid,'00000000-0000-0000-0000-000000000001','S'||(n-2),'00000000-0000-0000-0000-000000000002','UNKNOWN' FROM generate_series(3,5) n;
INSERT INTO renewal_packages(id,code,name,duration_days,price_vnd,enabled) VALUES
('00000000-0000-0000-0000-000000000030','D30','Gói 30 ngày',30,30000,true),
('00000000-0000-0000-0000-000000000090','D90','Gói 90 ngày',90,80000,true),
('00000000-0000-0000-0000-000000000365','D365','Gói 365 ngày',365,300000,true);
''')
migration=ROOT/'backend/src/main/resources/db/migration/V1__initial_schema.sql'
migration.parent.mkdir(parents=True,exist_ok=True)
migration.write_text('\n'.join(sql),encoding='utf-8')
