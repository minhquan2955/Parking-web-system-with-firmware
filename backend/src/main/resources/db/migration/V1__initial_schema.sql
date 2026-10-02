-- SRS 2.3: never edit this migration after it has run.
CREATE SEQUENCE provider_order_code_seq START 100000;
CREATE TABLE users (
  id uuid PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  username varchar(255) NOT NULL,
  password_hash varchar(255) NOT NULL,
  full_name varchar(255) NOT NULL,
  phone varchar(255),
  role varchar(255) NOT NULL,
  status varchar(255) NOT NULL
);
CREATE TABLE cards (
  id uuid PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  uid varchar(255) NOT NULL,
  owner_id uuid NOT NULL,
  status varchar(255) NOT NULL,
  expires_at timestamptz
);
CREATE TABLE renewal_packages (
  id uuid PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  code varchar(255) NOT NULL,
  name varchar(255) NOT NULL,
  duration_days integer NOT NULL,
  price_vnd numeric(12,0) NOT NULL,
  enabled boolean NOT NULL
);
CREATE TABLE renewal_orders (
  id uuid PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  card_id uuid NOT NULL,
  owner_id_snapshot uuid NOT NULL,
  created_by uuid NOT NULL,
  package_id uuid NOT NULL,
  package_code_snapshot varchar(255) NOT NULL,
  duration_days_snapshot integer NOT NULL,
  amount_vnd numeric(12,0) NOT NULL,
  currency varchar(255) NOT NULL,
  provider varchar(255) NOT NULL,
  provider_order_code bigint NOT NULL,
  provider_payment_id varchar(255),
  qr_payload text,
  checkout_url text,
  status varchar(255) NOT NULL,
  expires_at timestamptz NOT NULL,
  paid_at timestamptz,
  idempotency_key uuid NOT NULL,
  request_hash varchar(255) NOT NULL,
  next_reconcile_at timestamptz
);
CREATE TABLE payment_receipts (
  id uuid PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  provider varchar(255) NOT NULL,
  transaction_ref varchar(255),
  delivery_hash varchar(255) NOT NULL,
  order_id uuid,
  provider_order_code bigint,
  amount_vnd numeric(12,0),
  currency varchar(255),
  paid_at timestamptz,
  verification_status varchar(255) NOT NULL,
  processing_status varchar(255) NOT NULL,
  sanitized_payload text NOT NULL,
  received_at timestamptz NOT NULL
);
CREATE TABLE card_renewals (
  id uuid PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  order_id uuid NOT NULL,
  card_id uuid NOT NULL,
  old_expires_at timestamptz,
  new_expires_at timestamptz NOT NULL,
  applied_at timestamptz NOT NULL
);
CREATE TABLE parking_lots (
  id uuid PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  code varchar(255) NOT NULL,
  capacity integer NOT NULL
);
CREATE TABLE parking_sessions (
  id uuid PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  lot_id uuid NOT NULL,
  card_id uuid NOT NULL,
  owner_id_snapshot uuid NOT NULL,
  status varchar(255) NOT NULL,
  entry_at timestamptz NOT NULL,
  exit_at timestamptz,
  closure_type varchar(255)
);
CREATE TABLE access_events (
  id uuid PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  device_id uuid NOT NULL,
  event_id uuid NOT NULL,
  boot_id uuid NOT NULL,
  gate varchar(255) NOT NULL,
  card_uid varchar(255) NOT NULL,
  card_id uuid,
  session_id uuid,
  decision varchar(255) NOT NULL,
  reason varchar(255) NOT NULL,
  request_hash varchar(255) NOT NULL,
  response_json text NOT NULL,
  received_at timestamptz NOT NULL
);
CREATE TABLE devices (
  id uuid PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  code varchar(255) NOT NULL,
  api_key_hash varchar(255) NOT NULL,
  enabled boolean NOT NULL,
  current_boot_id uuid,
  last_seen_at timestamptz,
  firmware_version varchar(255),
  uptime_ms bigint
);
CREATE TABLE device_boots (
  id uuid PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  device_id uuid NOT NULL,
  boot_id uuid NOT NULL,
  last_seq bigint NOT NULL,
  first_seen_at timestamptz NOT NULL
);
CREATE TABLE parking_slots (
  id uuid PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  lot_id uuid NOT NULL,
  code varchar(255) NOT NULL,
  device_id uuid NOT NULL,
  reported_state varchar(255) NOT NULL,
  reported_at timestamptz
);
CREATE TABLE audit_logs (
  id uuid PRIMARY KEY,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  actor_type varchar(255) NOT NULL,
  actor_id uuid,
  action varchar(255) NOT NULL,
  entity_type varchar(255) NOT NULL,
  entity_id uuid NOT NULL,
  before_json text NOT NULL,
  after_json text NOT NULL,
  reason text
);
ALTER TABLE cards ADD FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE RESTRICT;
ALTER TABLE renewal_orders ADD FOREIGN KEY (card_id) REFERENCES cards(id) ON DELETE RESTRICT;
ALTER TABLE renewal_orders ADD FOREIGN KEY (owner_id_snapshot) REFERENCES users(id) ON DELETE RESTRICT;
ALTER TABLE renewal_orders ADD FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT;
ALTER TABLE renewal_orders ADD FOREIGN KEY (package_id) REFERENCES renewal_packages(id) ON DELETE RESTRICT;
ALTER TABLE payment_receipts ADD FOREIGN KEY (order_id) REFERENCES renewal_orders(id) ON DELETE RESTRICT;
ALTER TABLE card_renewals ADD FOREIGN KEY (card_id) REFERENCES cards(id) ON DELETE RESTRICT;
ALTER TABLE parking_sessions ADD FOREIGN KEY (lot_id) REFERENCES parking_lots(id) ON DELETE RESTRICT;
ALTER TABLE parking_sessions ADD FOREIGN KEY (card_id) REFERENCES cards(id) ON DELETE RESTRICT;
ALTER TABLE parking_sessions ADD FOREIGN KEY (owner_id_snapshot) REFERENCES users(id) ON DELETE RESTRICT;
ALTER TABLE access_events ADD FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE RESTRICT;
ALTER TABLE access_events ADD FOREIGN KEY (card_id) REFERENCES cards(id) ON DELETE RESTRICT;
ALTER TABLE access_events ADD FOREIGN KEY (session_id) REFERENCES parking_sessions(id) ON DELETE RESTRICT;
ALTER TABLE device_boots ADD FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE RESTRICT;
ALTER TABLE parking_slots ADD FOREIGN KEY (lot_id) REFERENCES parking_lots(id) ON DELETE RESTRICT;
ALTER TABLE parking_slots ADD FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE RESTRICT;

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
