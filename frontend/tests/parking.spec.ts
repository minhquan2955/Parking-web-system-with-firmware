import { test, expect, APIRequestContext } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
const env = Object.fromEntries(
  readFileSync(resolve(__dirname, "../../.env"), "utf8")
    .split(/\r?\n/)
    .filter((l) => /^[A-Z_]+=/.test(l))
    .map((l) => {
      const i = l.indexOf("=");
      return [l.slice(0, i), l.slice(i + 1)];
    }),
);
async function mutation(
  request: APIRequestContext,
  path: string,
  body: object,
  headers: Record<string, string> = {},
) {
  const token = await (await request.get("/api/v1/auth/csrf")).json();
  const response = await request.post("/api/v1" + path, {
    data: body,
    headers: { [token.headerName]: token.token, ...headers },
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  return response.json();
}
test("public registration, USER empty state, ADMIN assignment, payment, parking and audit", async ({
  page,
  request,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  const username = "e2e" + Date.now(),
    password = "Browser-test-123456";
  await page.goto("/register");
  await page.getByLabel("Tên đăng nhập", { exact: true }).fill(username);
  await page.getByLabel("Họ và tên", { exact: true }).fill("Khách kiểm thử");
  await page.getByLabel("Mật khẩu", { exact: true }).fill(password);
  await page.getByLabel("Xác nhận mật khẩu").fill(password);
  await page.getByRole("button", { name: "Tạo tài khoản" }).click();
  await expect(page).toHaveURL(/\/login/);
  await page.getByLabel("Tên đăng nhập", { exact: true }).fill(username);
  await page.getByLabel("Mật khẩu", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await expect(page).toHaveURL(/\/dashboard/);
  await expect(
    page.getByText(
      "Bạn chưa có thẻ. Vui lòng liên hệ quản trị viên để được cấp thẻ.",
    ),
  ).toBeVisible();
  await page.goto("/admin/users");
  await expect(
    page.getByRole("heading", { name: "Không có quyền truy cập" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Đăng xuất" }).click();
  await expect(page).toHaveURL(/\/login/);
  await page
    .getByLabel("Tên đăng nhập", { exact: true })
    .fill(env.ADMIN_USERNAME);
  await page.getByLabel("Mật khẩu", { exact: true }).fill(env.ADMIN_PASSWORD);
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await expect(page).toHaveURL(/\/dashboard/);
  await page.goto("/admin/cards");
  await page.getByRole("button", { name: "Cấp thẻ mới" }).click();
  await page.getByLabel("Tìm chủ thẻ").fill(username);
  const select = page.getByRole("combobox", {
    name: "Người dùng",
    exact: true,
  });
  await expect(select.locator("option")).toHaveCount(2);
  await select.selectOption({ label: "Khách kiểm thử · " + username });
  const uid = Date.now().toString(16).slice(-8).toUpperCase();
  await page.getByLabel("UID đọc từ thẻ vật lý").fill(uid);
  await page.getByRole("button", { name: "Cấp thẻ", exact: true }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.getByRole("link", { name: uid + " ↗" }).click();
  await expect(page.getByText("Chọn gói gia hạn")).toBeVisible();
  await page.getByRole("button", { name: "Chọn gói" }).first().click();
  await expect(page).toHaveURL(/\/payments\//);
  await expect(page.getByText("THANH TOÁN MÔ PHỎNG")).toBeVisible();
  await page.getByRole("button", { name: "Callback lặp", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Đã thanh toán — Đã gia hạn" }),
  ).toBeVisible();
  // Simulator device API still runs independently of browser sessions.
  const boot = crypto.randomUUID();
  const headers = { "X-Device-Id": "ESP32-01", "X-Device-Key": env.DEVICE_KEY };
  const hb = await request.post("/api/v1/device/heartbeats", {
    headers,
    data: {
      bootId: boot,
      seq: 1,
      uptimeMs: 0,
      firmwareVersion: "e2e-simulator",
      slots: ["S1", "S2", "S3"].map((slotId) => ({ slotId, state: "FREE" })),
    },
  });
  expect(hb.ok()).toBeTruthy();
  const entry = await request.post("/api/v1/device/access-events", {
    headers,
    data: {
      eventId: crypto.randomUUID(),
      bootId: boot,
      gate: "IN",
      cardUid: uid,
    },
  });
  expect((await entry.json()).reason).toBe("ENTRY_ALLOWED");
  const exit = await request.post("/api/v1/device/access-events", {
    headers,
    data: {
      eventId: crypto.randomUUID(),
      bootId: boot,
      gate: "OUT",
      cardUid: uid,
    },
  });
  expect((await exit.json()).reason).toBe("EXIT_ALLOWED");
  await page.goto("/parking-history");
  await expect(
    page.getByRole("cell", { name: uid, exact: true }),
  ).toBeVisible();
  await page.goto("/admin/access-events");
  await expect(
    page.getByRole("cell", { name: uid, exact: true }).first(),
  ).toBeVisible();
  await page.goto("/admin/audit");
  await expect(
    page.getByRole("cell", { name: "CARD_RENEWED", exact: true }).first(),
  ).toBeVisible();
  await page.goto("/dashboard");
  await expect(
    page.getByText("Không gian bãi đỗ", { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: resolve(__dirname, "../../docs/dashboard-desktop.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: resolve(__dirname, "../../docs/dashboard-mobile.png"),
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy();
  expect(errors).toEqual([]);
});
test("login rejects missing CSRF and device API rejects cookie-only authentication", async ({
  request,
}) => {
  const login = await request.post("/api/v1/auth/login", {
    data: { username: env.ADMIN_USERNAME, password: env.ADMIN_PASSWORD },
  });
  expect(login.status()).toBe(403);
  const device = await request.post("/api/v1/device/heartbeats", { data: {} });
  expect(device.status()).toBe(401);
});
test("credential form cannot submit before JavaScript hydration", async ({
  browser,
}) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();
  await page.goto("http://127.0.0.1:3000/login");
  await expect(
    page.getByRole("button", { name: "Đăng nhập", exact: true }),
  ).toBeDisabled();
  await expect(page.locator("form")).toHaveAttribute("method", "post");
  await context.close();
});
test("ten sensor changes reach the visible dashboard and stale heartbeat becomes UNKNOWN", async ({
  page,
  request,
}) => {
  test.setTimeout(70000);
  await page.goto("/login");
  await page
    .getByLabel("Tên đăng nhập", { exact: true })
    .fill(env.ADMIN_USERNAME);
  await page.getByLabel("Mật khẩu", { exact: true }).fill(env.ADMIN_PASSWORD);
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await expect(page).toHaveURL(/\/dashboard/);
  const boot = crypto.randomUUID(),
    headers = { "X-Device-Id": "ESP32-01", "X-Device-Key": env.DEVICE_KEY };
  for (let i = 0; i < 10; i++) {
    const state = i % 2 === 0 ? "OCCUPIED" : "FREE";
    const response = await request.post("/api/v1/device/heartbeats", {
      headers,
      data: {
        bootId: boot,
        seq: i + 1,
        uptimeMs: i * 3000,
        firmwareVersion: "polling-test",
        slots: [
          { slotId: "S1", state },
          { slotId: "S2", state: "FREE" },
          { slotId: "S3", state: "FREE" },
        ],
      },
    });
    expect(response.ok()).toBeTruthy();
    await expect(page.locator(".slot").first()).toHaveClass(
      new RegExp(state.toLowerCase()),
      { timeout: 5000 },
    );
  }
  await expect(page.locator(".device-stat")).toContainText("Mất kết nối", {
    timeout: 19000,
  });
  await expect(page.locator(".slot.unknown")).toHaveCount(3);
  // API failure must be distinct from an independently reported device OFFLINE state.
  await page.route("**/api/v1/parking/occupancy", (route) => route.abort());
  await expect(
    page.getByRole("alert").filter({ hasText: "Không kết nối được hệ thống" }),
  ).toBeVisible({ timeout: 5000 });
});
