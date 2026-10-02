import unittest
from simulator import Simulator, GateDriver


class SimulatorTests(unittest.TestCase):
    def response(self, body):
        return 200, {**body, 'decision': 'ALLOW', 'command': 'OPEN',
                     'openDurationMs': 3000, 'validUntil': '2030-01-01T00:00:00Z'}

    def test_retry_reuses_event_and_executes_once(self):
        requests = []
        def transport(endpoint, body, timeout):
            requests.append(body.copy())
            if len(requests) == 1:
                raise TimeoutError()
            return self.response(body)
        sim = Simulator('', '', transport)
        sim.registered = True
        sim.scan('IN', '04A10B7C')
        self.assertEqual(requests[0]['eventId'], requests[1]['eventId'])
        self.assertEqual(len(sim.driver.opens), 1)

    def test_late_response_does_not_open(self):
        now = [0]
        def transport(endpoint, body, timeout):
            now[0] += 3.1
            return self.response(body)
        sim = Simulator('', '', transport, lambda: now[0])
        sim.registered = True
        self.assertEqual(sim.scan('IN', '04A10B7C')['error'], 'NEEDS_REVIEW')
        self.assertFalse(sim.driver.opens)

    def test_reboot_does_not_execute_inflight_response(self):
        def transport(endpoint, body, timeout):
            sim.reboot()
            return self.response(body)
        sim = Simulator('', '', transport)
        sim.registered = True
        sim.scan('OUT', '04A10B7C')
        self.assertFalse(sim.driver.opens)

    def test_no_offline_authorization_and_no_retry_4xx(self):
        requests = []
        def transport(endpoint, body, timeout):
            requests.append(body)
            return 409, {'code': 'DEVICE_BOOT_NOT_REGISTERED'}
        sim = Simulator('', '', transport)
        sim.registered = True
        sim.scan('IN', '04A10B7C')
        self.assertEqual(len(requests), 1)
        self.assertFalse(sim.driver.opens)

    def test_driver_dedup_before_open(self):
        driver = GateDriver()
        self.assertTrue(driver.open_once('event', 'IN', 3000))
        self.assertFalse(driver.open_once('event', 'IN', 3000))


if __name__ == '__main__':
    unittest.main()
