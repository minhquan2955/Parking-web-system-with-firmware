#!/usr/bin/env python3
"""Hardware-free ESP32 protocol simulator. Standard library only; never drives a real gate."""
import argparse
import json
import os
import threading
import time
import urllib.request
import urllib.error
import uuid


class GateDriver:
    def __init__(self):
        self.executed = set()
        self.opens = []

    def open_once(self, event, gate, duration_ms):
        if event in self.executed:
            return False
        self.executed.add(event)  # Record BEFORE invoking the actuator.
        self.opens.append((event, gate, duration_ms))
        print(json.dumps({'driver': 'SIMULATED', 'command': 'OPEN', 'gate': gate,
                          'eventId': event, 'durationMs': duration_ms}), flush=True)
        return True


class Simulator:
    def __init__(self, base_url, key, transport=None, clock=time.monotonic):
        self.url = base_url.rstrip('/')
        self.key = key
        self.transport = transport or self.http
        self.clock = clock
        self.driver = GateDriver()
        self.state_lock = threading.RLock()
        self.scan_lock = threading.Lock()
        self.heartbeat_lock = threading.Lock()
        self.slots = {s: 'UNKNOWN' for s in ('S1', 'S2', 'S3')}
        self.offline = False
        self.stopped = threading.Event()
        self.changed = threading.Event()
        self.last_reads = {}
        self.reboot()

    def reboot(self):
        with self.state_lock:
            self.boot = str(uuid.uuid4())
            self.seq = 0
            self.started = self.clock()
            self.registered = False
            self.last_reads.clear()
        # Old events are never queued or replayed, and in-flight old responses fail boot checks.
        self.changed.set()

    def http(self, endpoint, body, timeout):
        if self.offline:
            raise ConnectionError('simulated network outage')
        request = urllib.request.Request(self.url + '/api/v1/device/' + endpoint,
            data=json.dumps(body).encode(), headers={'Content-Type': 'application/json',
            'X-Device-Id': 'ESP32-01', 'X-Device-Key': self.key}, method='POST')
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def heartbeat(self):
        with self.heartbeat_lock:
            with self.state_lock:
                boot = self.boot
                self.seq += 1
                body = {'bootId': boot, 'seq': self.seq,
                        'uptimeMs': int((self.clock() - self.started) * 1000),
                        'firmwareVersion': 'simulator-1.0.0',
                        'slots': [{'slotId': k, 'state': v} for k, v in self.slots.items()]}
            try:
                status, response = self.transport('heartbeats', body, 1)
                with self.state_lock:
                    if boot == self.boot and status == 200 and response.get('accepted'):
                        self.registered = True
                return response
            except (OSError, ValueError):
                return {'error': 'BACKEND_UNAVAILABLE'}

    def set_slot(self, slot, state):
        if slot not in self.slots or state not in ('FREE', 'OCCUPIED', 'UNKNOWN'):
            raise ValueError('Use S1/S2/S3 and FREE/OCCUPIED/UNKNOWN')
        with self.state_lock:
            self.slots[slot] = state
        self.changed.set()

    def scan(self, gate, uid):
        if gate not in ('IN', 'OUT'):
            raise ValueError('Reader gate must be IN or OUT')
        if not self.scan_lock.acquire(blocking=False):
            return {'error': 'BUSY'}
        try:
            now = self.clock()
            read_key = (gate, uid)
            if now - self.last_reads.get(read_key, -100) < 2:
                return {'error': 'DEBOUNCED'}
            self.last_reads[read_key] = now
            if not self.registered:
                return {'error': 'DEVICE_BOOT_NOT_REGISTERED'}
            boot = self.boot
            event = str(uuid.uuid4())
            body = {'bootId': boot, 'eventId': event, 'gate': gate, 'cardUid': uid}
            deadline = now + 3
            for attempt in range(3):
                remaining = deadline - self.clock()
                if remaining <= 0:
                    break
                try:
                    status, response = self.transport('access-events', body, min(1, remaining))
                except (OSError, ValueError):
                    continue
                if 400 <= status < 500:
                    return response
                if status != 200:
                    continue
                if self.clock() >= deadline or self.boot != boot:
                    break
                if response.get('eventId') != event or response.get('gate') != gate:
                    return {'error': 'INVALID_RESPONSE'}
                if response.get('decision') == 'ALLOW':
                    if response.get('command') != 'OPEN' or not response.get('validUntil'):
                        return {'error': 'INVALID_RESPONSE'}
                    duration = response.get('openDurationMs')
                    if not isinstance(duration, int) or not 0 < duration <= 10000:
                        return {'error': 'INVALID_RESPONSE'}
                    self.driver.open_once(event, gate, duration)
                return response
            return {'error': 'NEEDS_REVIEW', 'eventId': event}
        finally:
            self.scan_lock.release()

    def heartbeat_loop(self):
        last = -100.0
        while not self.stopped.is_set():
            elapsed = self.clock() - last
            if elapsed >= 5 or (self.changed.is_set() and elapsed >= 1):
                if self.changed.is_set():
                    self.stopped.wait(0.3)
                self.changed.clear()
                self.heartbeat()
                last = self.clock()
            self.stopped.wait(0.05)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default=os.getenv('PUBLIC_URL', 'http://localhost:3000'))
    args = parser.parse_args()
    key = os.getenv('DEVICE_KEY')
    if not key:
        parser.error('Set DEVICE_KEY from your local .env; never commit it.')
    simulator = Simulator(args.url, key)
    simulator.heartbeat()
    thread = threading.Thread(target=simulator.heartbeat_loop, daemon=True)
    thread.start()
    print('SIMULATOR: slot S1 FREE | scan IN UID | scan OUT UID | offline on/off | reboot | status | quit', flush=True)
    try:
        while True:
            command = input('> ').strip().split()
            if not command:
                continue
            try:
                match command:
                    case ['quit']:
                        break
                    case ['slot', slot, state]:
                        simulator.set_slot(slot, state)
                    case ['scan', gate, uid]:
                        print(json.dumps(simulator.scan(gate, uid), ensure_ascii=False))
                    case ['offline', state]:
                        simulator.offline = state == 'on'
                        simulator.changed.set()
                    case ['reboot']:
                        simulator.reboot()
                        simulator.heartbeat()
                    case ['status']:
                        print(json.dumps({'bootId': simulator.boot, 'registered': simulator.registered,
                                          'slots': simulator.slots, 'offline': simulator.offline}))
                    case _:
                        print('Unknown command')
            except ValueError as error:
                print(error)
    except (EOFError, KeyboardInterrupt):
        pass
    finally:
        simulator.stopped.set()
        thread.join(timeout=2)


if __name__ == '__main__':
    main()
