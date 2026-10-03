"use client";
import { useEffect, useState, useRef, FormEvent } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import QRCode from "qrcode";
import { api, mutate, ApiError, Row, date, money } from "@/lib/api";
import { useResource } from "@/lib/useResource";

const labels: Record<string, string> = {
  ACTIVE: "Đang hoạt động",
  DISABLED: "Đã vô hiệu hóa",
  ENABLED: "Đã bật",
  BLOCKED: "Đã khóa",
  EXPIRED: "Hết hạn",
  NOT_ACTIVATED: "Chưa kích hoạt",
  ONLINE: "Đang kết nối",
  OFFLINE: "Mất kết nối",
  NEVER_SEEN: "Chưa kết nối",
  FREE: "Còn trống",
  OCCUPIED: "Có xe",
  UNKNOWN: "Chưa xác định",
  OPEN: "Đang trong bãi",
  CLOSED: "Đã kết thúc",
  VOIDED: "Đã hủy lượt vào",
  CREATING: "Đang tạo QR",
  PENDING: "Chờ thanh toán",
  PAID: "Đã thanh toán",
  FAILED: "Không thành công",
  REVIEW: "Cần tra soát",
  ALLOW: "Cho phép",
  DENY: "Từ chối",
  APPLIED: "Đã gia hạn",
  UNMATCHED: "Chưa khớp đơn",
  EXCESS_PAYMENT_REVIEW: "Thanh toán dư",
  ENTRY_ALLOWED: "Được phép vào",
  EXIT_ALLOWED: "Được phép ra",
  UNKNOWN_CARD: "Thẻ chưa đăng ký",
  CARD_BLOCKED: "Thẻ bị khóa",
  USER_DISABLED: "Tài khoản bị khóa",
  CARD_EXPIRED: "Thẻ chưa có hạn hợp lệ",
  ALREADY_INSIDE: "Đang có phiên đỗ",
  OCCUPANCY_UNKNOWN: "Chưa xác định sức chứa",
  PARKING_FULL: "Không còn sức chứa",
  NO_ACTIVE_SESSION: "Không có phiên đang mở",
};
function Icon({ name = "grid" }: { name?: string }) {
  const paths: Record<string, string> = {
    grid: "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
    card: "M3 5h18v14H3z M3 10h18 M7 15h3",
    history: "M4 4v5h5 M4 9a8 8 0 1 1 0 7 M12 8v5l3 2",
    user: "M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0 M4 21v-2a8 8 0 0 1 16 0v2",
    device: "M7 3h10v18H7z M10 6h4 M11 18h2",
    arrow: "M5 12h14 M13 6l6 6-6 6",
    car: "M5 9l2-5h10l2 5 M3 9h18v9H3z M5 18v3 M19 18v3 M6 13h2 M16 13h2",
    logout: "M10 4H4v16h6 M9 12h12 M17 8l4 4-4 4",
    check: "M4 12l5 5L20 6",
  };
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={paths[name] || paths.grid} />
    </svg>
  );
}
function Badge({ value }: { value: unknown }) {
  const s = String(value ?? "UNKNOWN");
  return <span className={"badge " + s.toLowerCase()}>{labels[s] || s}</span>;
}
function ErrorBox({
  error,
  retry,
}: {
  error: Error | null;
  retry?: () => void;
}) {
  return error ? (
    <div className="notice error" role="alert">
      {error.message}
      {error instanceof ApiError &&
        error.retryAfter &&
        ` · Thử lại sau ${error.retryAfter} giây.`}
      {retry && (
        <button className="text-button" onClick={retry}>
          Thử lại
        </button>
      )}
    </div>
  ) : null;
}
function Empty({ children }: { children: React.ReactNode }) {
  return (
    <div className="empty">
      <span className="empty-icon">
        <Icon name="card" />
      </span>
      {children}
    </div>
  );
}
function Heading({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow?: string;
  title: string;
  description?: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="page-heading">
      <div>
        <div className="eyebrow">{eyebrow || "MAIN PARKING"}</div>
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {action}
    </div>
  );
}
function Field({
  label,
  ...props
}: React.InputHTMLAttributes<HTMLInputElement> & { label: string }) {
  return (
    <label className="field">
      <span>{label}</span>
      <input {...props} />
    </label>
  );
}
function Form({
  children,
  onSubmit,
  label = "Lưu thay đổi",
  onDone,
}: {
  children: React.ReactNode;
  onSubmit: (data: FormData) => Promise<unknown>;
  label?: string;
  onDone?: () => void;
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<Error | null>(null),
    [success, setSuccess] = useState(false),
    [ready, setReady] = useState(false);
  useEffect(() => setReady(true), []);
  return (
    <form
      method="post"
      onSubmit={async (e) => {
        e.preventDefault();
        if (busy) return;
        const data = new FormData(e.currentTarget);
        setBusy(true);
        setError(null);
        setSuccess(false);
        try {
          await onSubmit(data);
          setSuccess(true);
          onDone?.();
        } catch (ex) {
          setError(ex as Error);
        } finally {
          setBusy(false);
        }
      }}
    >
      <div className="form-fields">{children}</div>
      <ErrorBox error={error} />
      {success && (
        <div className="notice success" role="status">
          Đã lưu thành công.
        </div>
      )}
      <button className="button primary" disabled={busy || !ready}>
        {busy ? "Đang xử lý…" : label}
        <Icon name="arrow" />
      </button>
    </form>
  );
}
const val = (d: FormData, k: string) => String(d.get(k) || "");

export default function ParkingApp() {
  const path = usePathname(),
    router = useRouter(),
    [user, setUser] = useState<Row | null>(null),
    [checking, setChecking] = useState(true),
    [error, setError] = useState<Error | null>(null);
  const publicPage = path === "/login" || path === "/register";
  useEffect(() => {
    let alive = true;
    api("/me")
      .then((u) => {
        if (alive) {
          setUser(u);
          if (path === "/" || publicPage) router.replace("/dashboard");
        }
      })
      .catch((e) => {
        if (alive) {
          if (e.status === 401) {
            setUser(null);
            if (!publicPage) router.replace("/login");
          } else setError(e);
        }
      })
      .finally(() => {
        if (alive) setChecking(false);
      });
    return () => {
      alive = false;
    };
  }, [path, router, publicPage]);
  if (publicPage && !user)
    return (
      <AuthPage
        register={path === "/register"}
        onLogin={(u) => {
          setUser(u);
          router.replace("/dashboard");
        }}
      />
    );
  if (checking || !user)
    return (
      <main className="boot">
        <div className="brand-mark">
          P<span>•</span>
        </div>
        <p>{error ? "Không kết nối được hệ thống" : "Đang kết nối bãi đỗ…"}</p>
        <ErrorBox error={error} retry={() => location.reload()} />
      </main>
    );
  const admin = user.role === "ADMIN";
  const nav = [
    ["/dashboard", "Tổng quan", "grid"],
    ["/cards", "Thẻ & gia hạn", "card"],
    ["/parking-history", "Lịch sử đỗ xe", "history"],
    ["/profile", "Hồ sơ của tôi", "user"],
  ];
  const adminNav = [
    ["/admin/users", "Người dùng", "user"],
    ["/admin/cards", "Quản lý thẻ", "card"],
    ["/admin/payments", "Thanh toán", "card"],
    ["/admin/devices", "Thiết bị", "device"],
    ["/admin/access-events", "Nhật ký quét", "history"],
    ["/admin/audit", "Nhật ký quản trị", "history"],
  ];
  let content: React.ReactNode;
  if (path.startsWith("/admin") && !admin)
    content = (
      <Heading
        title="Không có quyền truy cập"
        description="Trang này dành cho quản trị viên."
      />
    );
  else if (path === "/dashboard" || path === "/")
    content = <Dashboard user={user} />;
  else if (path === "/profile")
    content = <Profile user={user} updated={setUser} />;
  else if (/^\/cards\/[^/]+$/.test(path))
    content = <CardDetail id={path.split("/")[2]} />;
  else if (/^\/payments\/[^/]+$/.test(path))
    content = <Payment id={path.split("/")[2]} admin={admin} />;
  else if (path === "/cards" || path === "/admin/cards")
    content = <Cards admin={path.startsWith("/admin")} />;
  else if (path === "/admin/users") content = <Users />;
  else if (path === "/admin/devices") content = <Devices />;
  else if (path === "/parking-history") content = <History admin={admin} />;
  else if (path === "/admin/payments") content = <Payments />;
  else if (path === "/admin/access-events") content = <Events />;
  else if (path === "/admin/audit") content = <Audit />;
  else
    content = (
      <Heading
        title="Không tìm thấy trang"
        description="Chọn một mục trong menu để tiếp tục."
      />
    );
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <Link href="/dashboard" className="brand">
          <div className="brand-mark">
            P<span>•</span>
          </div>
          <div>
            MAIN<span>SMART PARKING</span>
          </div>
        </Link>
        <div className="nav-label">KHÔNG GIAN CỦA BẠN</div>
        <nav>
          {nav.map(([href, text, icon]) => (
            <Link
              key={href}
              href={href}
              className={
                path === href ||
                (href === "/cards" && path.startsWith("/cards/"))
                  ? "active"
                  : ""
              }
            >
              <Icon name={icon} />
              {text}
            </Link>
          ))}
        </nav>
        {admin && (
          <>
            <div className="nav-label">QUẢN TRỊ BÃI ĐỖ</div>
            <nav>
              {adminNav.map(([href, text, icon]) => (
                <Link
                  key={href}
                  href={href}
                  className={path === href ? "active" : ""}
                >
                  <Icon name={icon} />
                  {text}
                </Link>
              ))}
            </nav>
          </>
        )}
        <div className="sidebar-bottom">
          <div className="lot-chip">
            <span className="dot" /> Bãi MAIN <span>03 chỗ</span>
          </div>
          <div className="user-chip">
            <div className="avatar">{String(user.fullName).charAt(0)}</div>
            <div>
              <strong>{user.fullName}</strong>
              <small>{admin ? "Quản trị viên" : "Thành viên"}</small>
            </div>
            <button
              className="icon-button"
              aria-label="Đăng xuất"
              onClick={async () => {
                try {
                  await api("/auth/logout", { method: "POST" });
                  setUser(null);
                  router.replace("/login");
                } catch (e) {
                  setError(e as Error);
                }
              }}
            >
              <Icon name="logout" />
            </button>
          </div>
        </div>
      </aside>
      <div className="main-area">
        <header className="topbar">
          <span>
            Bãi đỗ MAIN <span className="separator">/</span>{" "}
            <strong>
              {[...nav, ...adminNav].find((n) => n[0] === path)?.[1] ||
                "Chi tiết"}
            </strong>
          </span>
          <span className="topbar-right">
            Hệ thống bãi đỗ thông minh <span className="small-mark">M</span>
          </span>
        </header>
        <main className="workspace">
          <ErrorBox error={error} />
          {content}
          <footer>
            MAIN PARKING <span>Thời gian hiển thị theo múi giờ Việt Nam</span>
          </footer>
        </main>
      </div>
    </div>
  );
}

function AuthPage({
  register,
  onLogin,
}: {
  register: boolean;
  onLogin: (u: Row) => void;
}) {
  const router = useRouter();
  const [registered, setRegistered] = useState(false);
  useEffect(() => {
    setRegistered(new URLSearchParams(location.search).has("registered"));
  }, []);
  return (
    <div className="auth-layout">
      <section className="auth-art">
        <Link href="/login" className="brand light">
          <div className="brand-mark">
            P<span>•</span>
          </div>
          <div>
            MAIN<span>SMART PARKING</span>
          </div>
        </Link>
        <div className="auth-copy">
          <div className="eyebrow">MỖI HÀNH TRÌNH, MỘT ĐIỂM DỪNG</div>
          <h1>
            Đỗ xe gọn gàng.
            <br />
            An tâm mỗi ngày.
          </h1>
          <p>
            Thẻ ra vào, gia hạn và trạng thái bãi đỗ.
            <br />
            Tất cả trong một không gian.
          </p>
          <div className="parking-illustration">
            {["01", "02", "03"].map((s, i) => (
              <div
                key={s}
                className={"draw-slot " + (i === 1 ? "draw-empty" : "")}
              >
                <span>{s}</span>
                {i !== 1 && (
                  <div className="draw-car">
                    <div />
                  </div>
                )}
                {i === 1 && <span className="draw-p">P</span>}
              </div>
            ))}
          </div>
        </div>
        <small>MÔ HÌNH BÃI ĐỖ THÔNG MINH · MAIN</small>
      </section>
      <section className="auth-form">
        <div className="auth-box">
          <div className="eyebrow">CHÀO MỪNG ĐẾN MAIN</div>
          <h1>{register ? "Tạo tài khoản" : "Chào bạn trở lại"}</h1>
          <p>
            {register
              ? "Bắt đầu quản lý thẻ và hành trình của bạn."
              : "Đăng nhập để xem bãi đỗ và quản lý thẻ của bạn."}
          </p>
          {registered && !register && (
            <div className="notice success">
              Đăng ký thành công. Hãy đăng nhập để tiếp tục.
            </div>
          )}
          <Form
            label={register ? "Tạo tài khoản" : "Đăng nhập"}
            onSubmit={async (d) => {
              if (register) {
                if (val(d, "password") !== val(d, "confirm"))
                  throw new Error("Mật khẩu xác nhận chưa khớp");
                await mutate("/auth/register", {
                  username: val(d, "username"),
                  password: val(d, "password"),
                  fullName: val(d, "fullName"),
                  phone: val(d, "phone") || null,
                });
                router.push("/login?registered=1");
              } else
                onLogin(
                  await mutate("/auth/login", {
                    username: val(d, "username"),
                    password: val(d, "password"),
                  }),
                );
            }}
          >
            <Field
              label="Tên đăng nhập"
              name="username"
              required
              minLength={3}
              maxLength={50}
              autoComplete="username"
              placeholder="Tên đăng nhập của bạn"
            />
            {register && (
              <Field
                label="Họ và tên"
                name="fullName"
                required
                maxLength={100}
                autoComplete="name"
              />
            )}
            <Field
              label="Mật khẩu"
              name="password"
              type="password"
              required
              minLength={register ? 10 : undefined}
              autoComplete={register ? "new-password" : "current-password"}
              placeholder="Nhập mật khẩu"
            />
            {register && (
              <>
                <Field
                  label="Xác nhận mật khẩu"
                  name="confirm"
                  type="password"
                  required
                  autoComplete="new-password"
                />
                <Field
                  label="Số điện thoại (không bắt buộc)"
                  name="phone"
                  maxLength={20}
                  autoComplete="tel"
                />
              </>
            )}
          </Form>
          <p className="auth-switch">
            {register ? "Đã có tài khoản?" : "Bạn chưa có tài khoản?"}{" "}
            <Link href={register ? "/login" : "/register"}>
              {register ? "Đăng nhập" : "Đăng ký ngay"} →
            </Link>
          </p>
          {register && (
            <p className="fine-print">
              Sau khi đăng ký, liên hệ quản trị viên để được cấp thẻ RFID.
            </p>
          )}
        </div>
      </section>
    </div>
  );
}

function Dashboard({ user }: { user: Row }) {
  const occupancy = useResource<Row>("/parking/occupancy", true),
    device = useResource<Row>("/devices/summary", true),
    cards = useResource<Row>("/cards?size=3");
  const o = occupancy.data,
    d = device.data;
  return (
    <>
      <Heading
        eyebrow="KHÔNG GIAN ĐỖ XE CỦA BẠN"
        title={`Xin chào, ${String(user.fullName)}`}
        description="Một góc nhìn rõ ràng cho mỗi lượt vào, ra."
        action={
          <span className="live-label">
            <span className="dot" /> Cập nhật mỗi 3 giây
          </span>
        }
      />
      <ErrorBox
        error={occupancy.error || device.error}
        retry={() => {
          occupancy.refresh();
          device.refresh();
        }}
      />
      <div className="stats-grid">
        <div className="stat featured">
          <div>
            Chỗ đỗ còn trống
            <Icon name="car" />
          </div>
          <strong>
            {o && !occupancy.error ? o.freeCount : "—"}
            <small>/ 3</small>
          </strong>
          <span>
            {occupancy.error
              ? "Dữ liệu cũ — chưa cập nhật"
              : o?.unknownCount
                ? "Có chỗ chưa xác định"
                : "Theo cảm biến tại bãi"}
          </span>
        </div>
        <div className="stat">
          <div>
            Chỗ đang sử dụng
            <Icon name="car" />
          </div>
          <strong>
            {o && !occupancy.error ? o.occupiedCount : "—"}
            <small>chỗ</small>
          </strong>
          <span>{o?.unknownCount ?? 3} chỗ chưa xác định</span>
        </div>
        <div className="stat">
          <div>
            Kết nối thiết bị
            <Icon name="device" />
          </div>
          <div className="device-stat">
            {device.error ? (
              <Badge value="UNKNOWN" />
            ) : (
              <Badge value={d?.status} />
            )}
          </div>
          <span>
            ESP32-01 ·{" "}
            {device.error
              ? "Chưa lấy được trạng thái"
              : "Theo heartbeat thiết bị"}
          </span>
        </div>
      </div>
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>Không gian bãi đỗ</h2>
            <p>Trạng thái thực tế của ba vị trí tại bãi MAIN</p>
          </div>
          <div className="legend">
            <span>
              <i className="green" />
              Còn trống
            </span>
            <span>
              <i className="orange" />
              Có xe
            </span>
            <span>
              <i />
              Chưa xác định
            </span>
          </div>
        </div>
        <div className="parking-map">
          <div className="lane">
            <span>↑ LỐI VÀO</span>
            <span>MAIN / KHU ĐỖ XE</span>
            <span>LỐI RA ↑</span>
          </div>
          <div className="slots">
            {(
              o?.slots ||
              ["S1", "S2", "S3"].map((slotId) => ({ slotId, state: "UNKNOWN" }))
            ).map((s: Row) => (
              <div
                key={s.slotId}
                className={
                  "slot " +
                  (occupancy.error ? "unknown" : s.state.toLowerCase())
                }
              >
                <div className="slot-top">
                  <span>VỊ TRÍ</span>
                  <strong>{s.slotId}</strong>
                </div>
                <div className="slot-center">
                  {s.state === "OCCUPIED" && !occupancy.error ? (
                    <Icon name="car" />
                  ) : (
                    <span className="parking-letter">
                      {s.state === "FREE" && !occupancy.error ? "P" : "—"}
                    </span>
                  )}
                </div>
                <Badge value={occupancy.error ? "UNKNOWN" : s.state} />
              </div>
            ))}
          </div>
          <div className="map-bottom">
            <span>↗ Di chuyển theo hướng dẫn tại bãi</span>
            <span>Cập nhật: {date(o?.updatedAt)}</span>
          </div>
        </div>
      </section>
      <div className="two-columns">
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>
                {user.role === "ADMIN" ? "Thẻ trong hệ thống" : "Thẻ của bạn"}
              </h2>
              <p>Quyền sử dụng và thời hạn thẻ</p>
            </div>
            <Link href="/cards" className="text-link">
              Xem tất cả ↗
            </Link>
          </div>
          {cards.data?.items?.length ? (
            cards.data.items.map((c: Row) => (
              <Link href={"/cards/" + c.id} className="card-row" key={c.id}>
                <div className="card-symbol">
                  <Icon name="card" />
                </div>
                <div>
                  <strong>{c.uid}</strong>
                  <small>Hạn sử dụng: {date(c.expiresAt)}</small>
                </div>
                <Badge value={c.effectiveStatus} />
              </Link>
            ))
          ) : (
            <Empty>
              Bạn chưa có thẻ. Vui lòng liên hệ quản trị viên để được cấp thẻ.
            </Empty>
          )}
        </section>
        <section className="help-panel">
          <span className="eyebrow">THUẬN TIỆN MỖI NGÀY</span>
          <h2>
            Sẵn sàng cho
            <br />
            hành trình tiếp theo.
          </h2>
          <p>Kiểm tra hạn thẻ và chọn gói gia hạn phù hợp trước khi đến bãi.</p>
          <Link href="/cards" className="button light-button">
            Quản lý thẻ của tôi <Icon name="arrow" />
          </Link>
          <div className="help-ring" />
        </section>
      </div>
    </>
  );
}

type Column = {
  key: string;
  title: string;
  render?: (r: Row) => React.ReactNode;
};
function DataTable({
  path,
  columns,
  empty = "Chưa có dữ liệu.",
  filters = true,
  onSelect,
  poll = false,
}: {
  path: string;
  columns: Column[];
  empty?: string;
  filters?: boolean;
  onSelect?: (r: Row) => void;
  poll?: boolean;
}) {
  const [page, setPage] = useState(0),
    [query, setQuery] = useState(""),
    [search, setSearch] = useState("");
  useEffect(() => {
    setPage(0);
  }, [path, search]);
  const resource = useResource<Row>(
    `${path}${path.includes("?") ? "&" : "?"}page=${page}&size=10${search ? "&search=" + encodeURIComponent(search) : ""}`,
    poll,
  );
  return (
    <section className="panel table-panel">
      {filters && (
        <form
          className="table-toolbar"
          onSubmit={(e) => {
            e.preventDefault();
            setSearch(query);
          }}
        >
          <input
            aria-label="Tìm kiếm"
            placeholder="Tìm theo tên, mã…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            maxLength={100}
          />
          <button className="button secondary">Tìm kiếm</button>
          <button
            type="button"
            className="text-button"
            onClick={resource.refresh}
          >
            Làm mới ↻
          </button>
        </form>
      )}
      <ErrorBox error={resource.error} retry={resource.refresh} />
      {resource.loading && !resource.data ? (
        <div className="empty">Đang tải dữ liệu…</div>
      ) : resource.data?.items?.length ? (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                {columns.map((c) => (
                  <th key={c.key}>{c.title}</th>
                ))}
                {onSelect && <th>Thao tác</th>}
              </tr>
            </thead>
            <tbody>
              {resource.data.items.map((r: Row) => (
                <tr key={r.id}>
                  {columns.map((c) => (
                    <td key={c.key}>
                      {c.render ? c.render(r) : String(r[c.key] ?? "—")}
                    </td>
                  ))}
                  {onSelect && (
                    <td>
                      <button
                        className="text-button"
                        onClick={() => onSelect(r)}
                      >
                        Chi tiết →
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty>{empty}</Empty>
      )}
      <div className="pagination">
        <span>{resource.data?.totalElements || 0} kết quả</span>
        <div>
          <button
            disabled={page === 0}
            onClick={() => setPage((p) => p - 1)}
            aria-label="Trang trước"
          >
            ←
          </button>
          <span>
            Trang {page + 1} / {Math.max(1, resource.data?.totalPages || 0)}
          </span>
          <button
            disabled={!resource.data || page + 1 >= resource.data.totalPages}
            onClick={() => setPage((p) => p + 1)}
            aria-label="Trang tiếp theo"
          >
            →
          </button>
        </div>
      </div>
    </section>
  );
}
function Modal({
  title,
  children,
  close,
}: {
  title: string;
  children: React.ReactNode;
  close: () => void;
}) {
  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape") close();
    };
    document.addEventListener("keydown", key);
    return () => document.removeEventListener("keydown", key);
  }, [close]);
  return (
    <div
      className="modal-backdrop"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) close();
      }}
    >
      <section
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <div className="panel-heading">
          <h2>{title}</h2>
          <button className="icon-button" aria-label="Đóng" onClick={close}>
            ×
          </button>
        </div>
        {children}
      </section>
    </div>
  );
}
function Cards({ admin }: { admin: boolean }) {
  const [create, setCreate] = useState(false),
    [selected, setSelected] = useState<Row | null>(null),
    [version, setVersion] = useState(0);
  return (
    <>
      <Heading
        title={admin ? "Quản lý thẻ RFID" : "Thẻ & gia hạn"}
        description={
          admin
            ? "Cấp thẻ vật lý, quản lý chủ thẻ và trạng thái sử dụng."
            : "Theo dõi thời hạn và quản lý các lần gia hạn của bạn."
        }
        action={
          admin && (
            <button className="button primary" onClick={() => setCreate(true)}>
              + Cấp thẻ mới
            </button>
          )
        }
      />
      <DataTable
        key={version}
        path="/cards"
        empty="Bạn chưa có thẻ. Vui lòng liên hệ quản trị viên để được cấp thẻ."
        columns={[
          {
            key: "uid",
            title: "Mã thẻ",
            render: (r) => (
              <Link className="text-link mono" href={"/cards/" + r.id}>
                {r.uid} ↗
              </Link>
            ),
          },
          { key: "ownerName", title: "Chủ thẻ" },
          {
            key: "effectiveStatus",
            title: "Trạng thái",
            render: (r) => <Badge value={r.effectiveStatus} />,
          },
          {
            key: "expiresAt",
            title: "Hạn sử dụng",
            render: (r) => (r.expiresAt ? date(r.expiresAt) : "Chưa kích hoạt"),
          },
          {
            key: "hasOpenSession",
            title: "Trong bãi",
            render: (r) => (r.hasOpenSession ? "Có phiên đang mở" : "—"),
          },
        ]}
        onSelect={admin ? setSelected : undefined}
      />
      {create && (
        <Modal title="Cấp thẻ cho người dùng" close={() => setCreate(false)}>
          <CreateCard
            onDone={() => {
              setCreate(false);
              setVersion((v) => v + 1);
            }}
          />
        </Modal>
      )}
      {selected && (
        <Modal
          title={"Trạng thái thẻ " + selected.uid}
          close={() => setSelected(null)}
        >
          <Form
            onSubmit={(d) =>
              mutate(
                "/admin/cards/" + selected.id + "/status",
                { status: val(d, "status"), reason: val(d, "reason") },
                "PATCH",
              )
            }
            onDone={() => {
              setSelected(null);
              setVersion((v) => v + 1);
            }}
          >
            <label className="field">
              <span>Trạng thái</span>
              <select name="status" defaultValue={selected.status}>
                <option value="ENABLED">Bật thẻ</option>
                <option value="BLOCKED">Khóa thẻ</option>
              </select>
            </label>
            <Field
              label="Lý do thay đổi"
              name="reason"
              required
              minLength={10}
              maxLength={500}
            />
          </Form>
        </Modal>
      )}
    </>
  );
}
function CreateCard({ onDone }: { onDone: () => void }) {
  const [search, setSearch] = useState("");
  const users = useResource<Row>(
    "/admin/users?size=100&search=" + encodeURIComponent(search),
  );
  return (
    <Form
      label="Cấp thẻ"
      onSubmit={(d) =>
        mutate("/admin/cards", {
          uid: val(d, "uid"),
          ownerId: val(d, "ownerId"),
        })
      }
      onDone={onDone}
    >
      <Field
        label="Tìm chủ thẻ"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        maxLength={100}
      />
      <label className="field">
        <span>Người dùng</span>
        <select required name="ownerId">
          <option value="">Chọn người dùng</option>
          {users.data?.items?.map((u: Row) => (
            <option key={u.id} value={u.id}>
              {u.fullName} · {u.username}
            </option>
          ))}
        </select>
      </label>
      <Field
        label="UID đọc từ thẻ vật lý"
        name="uid"
        required
        placeholder="04A10B7C"
      />
      <p className="fine-print">
        Thẻ mới chưa có hạn sử dụng. Chủ thẻ cần thanh toán gói gia hạn.
      </p>
    </Form>
  );
}
function newIdempotencyKey() {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function CardDetail({ id }: { id: string }) {
  const c = useResource<Row>("/cards/" + id),
    p = useResource<Row[]>("/renewal-packages");
  const router = useRouter();
  const keys = useRef<Record<string, string>>({});
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<Error | null>(null);
  return (
    <>
      <Heading
        title="Chi tiết thẻ"
        description="Quyền sử dụng và lịch sử gia hạn được lưu theo từng đơn."
      />
      <ErrorBox error={c.error || p.error || error} retry={c.refresh} />
      {c.data && (
        <>
          <div className="detail-card">
            <Icon name="card" />
            <div>
              <span>THẺ RFID</span>
              <h2 className="mono">{c.data.uid}</h2>
              <p>
                {c.data.ownerName} · Hạn: {date(c.data.expiresAt)}
              </p>
            </div>
            <Badge value={c.data.effectiveStatus} />
          </div>
          <h2 className="section-title">Chọn gói gia hạn</h2>
          <div className="package-grid">
            {p.data?.map((pack) => (
              <div className="package" key={pack.id}>
                <span className="eyebrow">{pack.code}</span>
                <h2>
                  {pack.durationDays}
                  <small> ngày</small>
                </h2>
                <strong>{money(pack.priceVnd)}</strong>
                <p>
                  Thời hạn tính theo ngày 24 giờ.
                  <br />
                  Cộng tiếp nếu thẻ còn hạn.
                </p>
                <button
                  disabled={busy || c.data?.status === "BLOCKED"}
                  className="button primary"
                  onClick={async () => {
                    setBusy(true);
                    setError(null);
                    try {
                      keys.current[pack.id] ??= newIdempotencyKey();
                      const o = await mutate(
                        "/cards/" + id + "/renewal-orders",
                        { packageId: pack.id },
                        "POST",
                        { "Idempotency-Key": keys.current[pack.id] },
                      );
                      router.push("/payments/" + o.id);
                    } catch (e) {
                      setError(e as Error);
                    } finally {
                      setBusy(false);
                    }
                  }}
                >
                  {busy ? "Đang xử lý…" : "Chọn gói"} <Icon name="arrow" />
                </button>
              </div>
            ))}
          </div>
          <p className="fine-print">
            Giá cấu hình demo. Chế độ thanh toán được hiển thị ở bước tiếp theo.
          </p>
          <h2 className="section-title">Đơn gia hạn</h2>
          <DataTable
            path={"/renewal-orders?cardId=" + id}
            filters={false}
            columns={[
              {
                key: "id",
                title: "Mã đơn",
                render: (r) => (
                  <Link className="text-link mono" href={"/payments/" + r.id}>
                    {r.id.slice(0, 8)} ↗
                  </Link>
                ),
              },
              { key: "packageCode", title: "Gói" },
              {
                key: "status",
                title: "Trạng thái",
                render: (r) => <Badge value={r.status} />,
              },
              {
                key: "createdAt",
                title: "Ngày tạo",
                render: (r) => date(r.createdAt),
              },
            ]}
          />
          <h2 className="section-title">Lịch sử gia hạn thành công</h2>
          <DataTable
            path={"/renewal-orders?cardId=" + id + "&applied=true"}
            filters={false}
            columns={[
              { key: "packageCode", title: "Gói" },
              {
                key: "amountVnd",
                title: "Số tiền",
                render: (r) => money(r.amountVnd),
              },
              {
                key: "oldExpiresAt",
                title: "Hạn trước",
                render: (r) =>
                  r.oldExpiresAt ? date(r.oldExpiresAt) : "Chưa kích hoạt",
              },
              {
                key: "newExpiresAt",
                title: "Hạn sau",
                render: (r) => date(r.newExpiresAt),
              },
              {
                key: "renewalAppliedAt",
                title: "Áp dụng lúc",
                render: (r) => date(r.renewalAppliedAt),
              },
            ]}
          />
        </>
      )}
    </>
  );
}
function Payment({ id, admin }: { id: string; admin: boolean }) {
  const resource = useResource<Row>("/renewal-orders/" + id, true, (o) =>
    ["PENDING", "CREATING"].includes(o.status),
  );
  const o = resource.data;
  const [qr, setQr] = useState(""),
    [now, setNow] = useState(Date.now()),
    [error, setError] = useState<Error | null>(null),
    [busy, setBusy] = useState(false);
  const offset = useRef(0);
  useEffect(() => {
    if (o) {
      offset.current = new Date(o.serverTime).getTime() - Date.now();
      setNow(Date.now() + offset.current);
    }
    setQr("");
    let live = true;
    if (o?.qrPayload)
      QRCode.toDataURL(o.qrPayload, { width: 280, margin: 2 }).then((q) => {
        if (live) setQr(q);
      });
    return () => {
      live = false;
    };
  }, [o]);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now() + offset.current), 1000);
    return () => clearInterval(timer);
  }, []);
  const remaining = o
    ? Math.max(0, Math.floor((new Date(o.expiresAt).getTime() - now) / 1000))
    : 0;
  async function action(body: Row, simulate = false, ownerSimulation = false) {
    setBusy(true);
    setError(null);
    try {
      await mutate(
        simulate
          ? (ownerSimulation ? "/renewal-orders/" : "/admin/dev/renewal-orders/") +
              id +
              "/simulate-payment"
          : "/renewal-orders/" + id + "/reconcile",
        body,
      );
      resource.refresh();
    } catch (e) {
      setError(e as Error);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <Heading
        title="Thanh toán gia hạn"
        description="Thẻ được gia hạn tự động sau khi hệ thống xác minh thanh toán."
      />
      <ErrorBox error={resource.error || error} retry={resource.refresh} />
      {o && (
        <div className="payment-layout">
          <section className="panel payment-qr">
            {o.paymentMode === "mock" && (
              <div className="notice warning">
                THANH TOÁN MÔ PHỎNG · Không chuyển tiền thật
              </div>
            )}
            <Badge value={o.status} />
            {o.status === "PAID" && o.renewalAppliedAt ? (
              <div className="paid-state">
                <span>
                  <Icon name="check" />
                </span>
                <h2>Đã thanh toán — Đã gia hạn</h2>
                <p>Hạn mới: {date(o.newExpiresAt)}</p>
                <Link className="button primary" href={"/cards/" + o.cardId}>
                  Xem thẻ của bạn
                </Link>
              </div>
            ) : o.status === "PENDING" && remaining > 0 ? (
              <>
                <h2>{money(o.amountVnd)}</h2>
                {qr ? (
                  <img
                    className="qr-image"
                    src={qr}
                    width="280"
                    height="280"
                    alt="Mã QR thanh toán gia hạn"
                  />
                ) : (
                  <p>Đang chuẩn bị mã QR…</p>
                )}
                {o.paymentMode === "mock" && (
                  <>
                    <p className="mock-payment-note">
                      QR mô phỏng, không dùng để chuyển tiền. Chọn kết quả thanh toán bên dưới.
                    </p>
                    <div className="dev-actions mock-payment-actions">
                      <button
                        disabled={busy}
                        className="button primary"
                        onClick={() => action({ scenario: "SUCCESS" }, true, true)}
                      >
                        Xác nhận thanh toán thành công
                      </button>
                      <button
                        disabled={busy}
                        className="button secondary"
                        onClick={() => action({ scenario: "FAILURE" }, true, true)}
                      >
                        Xác nhận thanh toán thất bại
                      </button>
                    </div>
                  </>
                )}
                <p>
                  QR còn hiệu lực{" "}
                  <strong className="mono">
                    {Math.floor(remaining / 60)}:
                    {String(remaining % 60).padStart(2, "0")}
                  </strong>
                </p>
                {o.checkoutUrl && /^https:\/\//.test(o.checkoutUrl) && (
                  <a
                    className="button primary"
                    href={o.checkoutUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    Mở trang thanh toán ↗
                  </a>
                )}
              </>
            ) : (
              <Empty>
                {o.status === "CREATING"
                  ? "Đang xác định kết quả tạo QR…"
                  : o.status === "REVIEW"
                    ? "Đơn cần tra soát. Hãy liên hệ quản trị viên."
                    : o.status === "FAILED"
                      ? "Thanh toán mô phỏng thất bại. Hãy quay lại thẻ để chọn gói và tạo đơn mới."
                    : "QR không còn được hiển thị. Bạn có thể kiểm tra lại thanh toán."}
              </Empty>
            )}
            {o.status !== "PAID" && (
              <button
                disabled={busy}
                className="button secondary"
                onClick={() => action({ acceptLatePayment: false })}
              >
                Kiểm tra thanh toán ↻
              </button>
            )}
          </section>
          <section className="panel order-info">
            <h2>Thông tin đơn</h2>
            <dl>
              <dt>Mã đơn</dt>
              <dd className="mono break">{o.id}</dd>
              <dt>Gói gia hạn</dt>
              <dd>
                {o.packageCode} · {o.durationDays} ngày
              </dd>
              <dt>Số tiền</dt>
              <dd>{money(o.amountVnd)}</dd>
              <dt>Tạo lúc</dt>
              <dd>{date(o.createdAt)}</dd>
              <dt>Hết hạn QR</dt>
              <dd>{date(o.expiresAt)}</dd>
              <dt>Gia hạn lúc</dt>
              <dd>{date(o.renewalAppliedAt)}</dd>
            </dl>
            <div className="notice">
              Bạn có thể đóng trang. Việc xác minh thanh toán và gia hạn vẫn
              được hệ thống tiếp tục xử lý.
            </div>
            {admin && o.paymentMode === "mock" && !["PAID", "FAILED"].includes(o.status) && (
              <div className="dev-actions">
                <h3>Kịch bản mô phỏng</h3>
                {[
                  ["SUCCESS", "Thành công"],
                  ["DUPLICATE", "Callback lặp"],
                  ["AMOUNT_MISMATCH", "Sai số tiền"],
                  ["LATE", "Trả tiền muộn"],
                ].map(([scenario, label]) => (
                  <button
                    disabled={busy}
                    key={scenario}
                    className="button secondary"
                    onClick={() => action({ scenario }, true)}
                  >
                    {label}
                  </button>
                ))}
              </div>
            )}
            {admin && o.status === "REVIEW" && (
              <Form
                label="Tra soát & nhận khoản trả muộn"
                onSubmit={(d) =>
                  mutate("/renewal-orders/" + id + "/reconcile", {
                    acceptLatePayment: true,
                    reason: val(d, "reason"),
                  })
                }
                onDone={resource.refresh}
              >
                <Field
                  label="Lý do nhận khoản trả muộn"
                  name="reason"
                  required
                  minLength={10}
                  maxLength={500}
                />
              </Form>
            )}
          </section>
        </div>
      )}
    </>
  );
}
function Profile({ user, updated }: { user: Row; updated: (u: Row) => void }) {
  const router = useRouter();
  return (
    <>
      <Heading
        title="Hồ sơ của tôi"
        description="Thông tin tài khoản và bảo mật đăng nhập."
      />
      <div className="two-columns">
        <section className="panel padded">
          <h2>Thông tin cá nhân</h2>
          <p className="muted">Tên đăng nhập: {user.username}</p>
          <Form
            onSubmit={async (d) => {
              updated(
                await mutate(
                  "/me",
                  {
                    fullName: val(d, "fullName"),
                    phone: val(d, "phone") || null,
                  },
                  "PATCH",
                ),
              );
            }}
          >
            <Field
              label="Họ và tên"
              name="fullName"
              defaultValue={user.fullName}
              required
              maxLength={100}
            />
            <Field
              label="Số điện thoại"
              name="phone"
              defaultValue={user.phone || ""}
              maxLength={20}
            />
          </Form>
        </section>
        <section className="panel padded">
          <h2>Đổi mật khẩu</h2>
          <p className="muted">
            Bạn sẽ cần đăng nhập lại sau khi đổi mật khẩu.
          </p>
          <Form
            label="Đổi mật khẩu"
            onSubmit={async (d) => {
              await mutate(
                "/me/password",
                {
                  currentPassword: val(d, "currentPassword"),
                  newPassword: val(d, "newPassword"),
                },
                "PUT",
              );
              router.replace("/login");
            }}
          >
            <Field
              label="Mật khẩu hiện tại"
              name="currentPassword"
              type="password"
              required
              autoComplete="current-password"
            />
            <Field
              label="Mật khẩu mới"
              name="newPassword"
              type="password"
              required
              minLength={10}
              autoComplete="new-password"
            />
          </Form>
        </section>
      </div>
    </>
  );
}
function Users() {
  const [selected, setSelected] = useState<Row | null>(null),
    [create, setCreate] = useState(false),
    [version, setVersion] = useState(0);
  return (
    <>
      <Heading
        title="Người dùng"
        description="Quản lý tài khoản thành viên và trạng thái truy cập."
        action={
          <button className="button primary" onClick={() => setCreate(true)}>
            + Tạo người dùng
          </button>
        }
      />
      <DataTable
        key={version}
        path="/admin/users"
        columns={[
          { key: "fullName", title: "Họ và tên" },
          { key: "username", title: "Tên đăng nhập" },
          { key: "phone", title: "Số điện thoại" },
          {
            key: "status",
            title: "Trạng thái",
            render: (r) => <Badge value={r.status} />,
          },
          {
            key: "createdAt",
            title: "Ngày tạo",
            render: (r) => date(r.createdAt),
          },
        ]}
        onSelect={setSelected}
      />
      {(create || selected) && (
        <Modal
          title={create ? "Tạo tài khoản USER" : "Chỉnh sửa người dùng"}
          close={() => {
            setCreate(false);
            setSelected(null);
          }}
        >
          <Form
            onSubmit={(d) =>
              create
                ? mutate("/admin/users", {
                    username: val(d, "username"),
                    initialPassword: val(d, "initialPassword"),
                    fullName: val(d, "fullName"),
                    phone: val(d, "phone") || null,
                  })
                : mutate(
                    "/admin/users/" + selected?.id,
                    {
                      fullName: val(d, "fullName"),
                      phone: val(d, "phone") || null,
                      status: val(d, "status"),
                    },
                    "PATCH",
                  )
            }
            onDone={() => {
              setCreate(false);
              setSelected(null);
              setVersion((v) => v + 1);
            }}
          >
            {create && (
              <>
                <Field
                  label="Tên đăng nhập"
                  name="username"
                  required
                  minLength={3}
                  maxLength={50}
                />
                <Field
                  label="Mật khẩu ban đầu"
                  name="initialPassword"
                  type="password"
                  required
                  minLength={10}
                  autoComplete="new-password"
                />
              </>
            )}
            <Field
              label="Họ và tên"
              name="fullName"
              defaultValue={selected?.fullName}
              required
              maxLength={100}
            />
            <Field
              label="Số điện thoại"
              name="phone"
              defaultValue={selected?.phone || ""}
              maxLength={20}
            />
            {!create && (
              <label className="field">
                <span>Trạng thái</span>
                <select name="status" defaultValue={selected?.status}>
                  <option value="ACTIVE">Hoạt động</option>
                  <option value="DISABLED">Vô hiệu hóa</option>
                </select>
              </label>
            )}
          </Form>
        </Modal>
      )}
    </>
  );
}
function DateFilters({
  onChange,
  statuses = [],
}: {
  onChange: (q: string) => void;
  statuses?: string[];
}) {
  return (
    <form
      className="filter-bar"
      onSubmit={(e) => {
        e.preventDefault();
        const d = new FormData(e.currentTarget),
          q = new URLSearchParams();
        for (const k of ["from", "to"])
          if (val(d, k)) q.set(k, new Date(val(d, k)).toISOString());
        if (val(d, "status")) q.set("status", val(d, "status"));
        onChange(q.toString());
      }}
    >
      <Field label="Từ thời điểm" name="from" type="datetime-local" />
      <Field label="Đến trước thời điểm" name="to" type="datetime-local" />
      {statuses.length > 0 && (
        <label className="field">
          <span>Trạng thái</span>
          <select name="status">
            <option value="">Tất cả</option>
            {statuses.map((s) => (
              <option key={s} value={s}>
                {labels[s] || s}
              </option>
            ))}
          </select>
        </label>
      )}
      <button className="button secondary">Áp dụng</button>
    </form>
  );
}
function History({ admin }: { admin: boolean }) {
  const [q, setQ] = useState(""),
    [selected, setSelected] = useState<Row | null>(null),
    [version, setVersion] = useState(0);
  return (
    <>
      <Heading
        title="Lịch sử đỗ xe"
        description="Các lượt vào, ra được hệ thống cho phép và các phiên đã hiệu chỉnh."
      />
      <DateFilters onChange={setQ} statuses={["OPEN", "CLOSED", "VOIDED"]} />
      <DataTable
        key={version}
        path={"/parking/sessions?" + q}
        filters={false}
        columns={[
          { key: "cardUid", title: "Thẻ" },
          { key: "ownerName", title: "Chủ thẻ" },
          { key: "entryAt", title: "Vào lúc", render: (r) => date(r.entryAt) },
          { key: "exitAt", title: "Ra lúc", render: (r) => date(r.exitAt) },
          {
            key: "status",
            title: "Trạng thái",
            render: (r) => <Badge value={r.status} />,
          },
        ]}
        onSelect={admin ? setSelected : undefined}
      />
      {selected && (
        <Modal title="Hiệu chỉnh phiên đỗ" close={() => setSelected(null)}>
          <p>
            Đối chiếu xe thực tế trước khi sửa. Thao tác được ghi nhật ký và
            không mở barrier.
          </p>
          <Form
            onSubmit={(d) =>
              mutate(
                "/admin/parking/sessions/" + selected.id + "/corrections",
                { action: val(d, "action"), reason: val(d, "reason") },
              )
            }
            onDone={() => {
              setSelected(null);
              setVersion((v) => v + 1);
            }}
          >
            <label className="field">
              <span>Thao tác</span>
              <select name="action" required>
                {selected.status === "OPEN" ? (
                  <>
                    <option value="VOID_ENTRY">
                      Hủy lượt vào chưa thực hiện
                    </option>
                    <option value="CLOSE">Đóng phiên thủ công</option>
                  </>
                ) : selected.status === "CLOSED" ? (
                  <option value="REOPEN">Mở lại phiên</option>
                ) : (
                  <option value="">Phiên đã hủy không thể sửa</option>
                )}
              </select>
            </label>
            <Field
              label="Lý do hiệu chỉnh"
              name="reason"
              required
              minLength={10}
              maxLength={500}
            />
          </Form>
        </Modal>
      )}
    </>
  );
}
function Payments() {
  const [tab, setTab] = useState("orders"),
    [q, setQ] = useState("");
  return (
    <>
      <Heading
        title="Thanh toán & tra soát"
        description="Theo dõi đơn gia hạn và xử lý các khoản thanh toán cần đối chiếu."
      />
      <div className="tabs">
        <button
          className={tab === "orders" ? "active" : ""}
          onClick={() => {
            setTab("orders");
            setQ("");
          }}
        >
          Đơn gia hạn
        </button>
        <button
          className={tab === "receipts" ? "active" : ""}
          onClick={() => {
            setTab("receipts");
            setQ("");
          }}
        >
          Biên nhận thanh toán
        </button>
      </div>
      {tab === "orders" ? (
        <>
          <DateFilters
            onChange={setQ}
            statuses={[
              "CREATING",
              "PENDING",
              "PAID",
              "EXPIRED",
              "REVIEW",
              "FAILED",
            ]}
          />
          <DataTable
            path={"/renewal-orders?" + q}
            filters={false}
            columns={[
              {
                key: "id",
                title: "Mã đơn",
                render: (r) => (
                  <Link className="text-link mono" href={"/payments/" + r.id}>
                    {r.id.slice(0, 8)} ↗
                  </Link>
                ),
              },
              { key: "packageCode", title: "Gói" },
              {
                key: "amountVnd",
                title: "Số tiền",
                render: (r) => money(r.amountVnd),
              },
              { key: "paymentMode", title: "Chế độ" },
              {
                key: "status",
                title: "Trạng thái",
                render: (r) => <Badge value={r.status} />,
              },
              {
                key: "createdAt",
                title: "Ngày tạo",
                render: (r) => date(r.createdAt),
              },
            ]}
          />
        </>
      ) : (
        <>
          <div className="filter-bar">
            <label className="field">
              <span>Kết quả xử lý</span>
              <select
                onChange={(e) =>
                  setQ(
                    e.target.value ? "processingStatus=" + e.target.value : "",
                  )
                }
              >
                <option value="">Tất cả</option>
                {[
                  "APPLIED",
                  "REVIEW",
                  "UNMATCHED",
                  "EXCESS_PAYMENT_REVIEW",
                ].map((s) => (
                  <option key={s} value={s}>
                    {labels[s]}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <DataTable
            path={"/admin/payment-receipts?" + q}
            filters={false}
            columns={[
              { key: "transactionRef", title: "Mã giao dịch" },
              { key: "providerOrderCode", title: "Mã nhà cung cấp" },
              {
                key: "amountVnd",
                title: "Số tiền",
                render: (r) => (r.amountVnd == null ? "—" : money(r.amountVnd)),
              },
              {
                key: "processingStatus",
                title: "Kết quả",
                render: (r) => <Badge value={r.processingStatus} />,
              },
              {
                key: "receivedAt",
                title: "Nhận lúc",
                render: (r) => date(r.receivedAt),
              },
            ]}
          />
        </>
      )}
    </>
  );
}
function Devices() {
  const r = useResource<Row[]>("/admin/devices", true);
  return (
    <>
      <Heading
        title="Thiết bị"
        description="Theo dõi heartbeat và thông tin ESP32 tại bãi."
      />
      <ErrorBox error={r.error} retry={r.refresh} />
      {r.data?.map((d) => (
        <section className="panel padded" key={d.code}>
          <div className="panel-heading">
            <h2>{d.code}</h2>
            {!r.error && <Badge value={d.status} />}
          </div>
          <dl>
            <dt>Heartbeat cuối</dt>
            <dd>{date(d.lastSeenAt)}</dd>
            <dt>Firmware</dt>
            <dd>{d.firmwareVersion || "Chưa có dữ liệu"}</dd>
            <dt>Uptime</dt>
            <dd>
              {d.uptimeMs == null
                ? "—"
                : Math.floor(d.uptimeMs / 1000) + " giây"}
            </dd>
            <dt>Boot hiện tại</dt>
            <dd className="mono break">{d.currentBootId || "—"}</dd>
          </dl>
          <p className="fine-print">
            Trạng thái kết nối dựa trên heartbeat, không xác nhận hoạt động cơ
            khí của barrier.
          </p>
        </section>
      ))}
    </>
  );
}
function Events() {
  const [q, setQ] = useState("");
  return (
    <>
      <Heading
        title="Nhật ký quét thẻ"
        description="Mọi quyết định cho phép và từ chối tại hai cổng."
      />
      <form
        className="filter-bar"
        onSubmit={(e) => {
          e.preventDefault();
          const d = new FormData(e.currentTarget),
            q = new URLSearchParams();
          for (const k of ["uid", "gate", "decision"])
            if (val(d, k)) q.set(k, val(d, k));
          setQ(q.toString());
        }}
      >
        <Field label="UID" name="uid" />
        <label className="field">
          <span>Cổng</span>
          <select name="gate">
            <option value="">Tất cả</option>
            <option>IN</option>
            <option>OUT</option>
          </select>
        </label>
        <label className="field">
          <span>Quyết định</span>
          <select name="decision">
            <option value="">Tất cả</option>
            <option>ALLOW</option>
            <option>DENY</option>
          </select>
        </label>
        <button className="button secondary">Lọc nhật ký</button>
      </form>
      <DataTable
        path={"/admin/access-events?" + q}
        filters={false}
        columns={[
          {
            key: "receivedAt",
            title: "Thời điểm",
            render: (r) => date(r.receivedAt),
          },
          { key: "cardUid", title: "UID" },
          { key: "gate", title: "Cổng" },
          {
            key: "decision",
            title: "Quyết định",
            render: (r) => <Badge value={r.decision} />,
          },
          {
            key: "reason",
            title: "Lý do",
            render: (r) => labels[r.reason] || r.reason,
          },
        ]}
      />
    </>
  );
}
function Audit() {
  const [q, setQ] = useState(""),
    [selected, setSelected] = useState<Row | null>(null);
  return (
    <>
      <Heading
        title="Nhật ký quản trị"
        description="Dấu vết thay đổi tài khoản, thẻ, phiên đỗ và gia hạn."
      />
      <DateFilters onChange={setQ} />
      <DataTable
        path={"/admin/audit-logs?" + q}
        filters={false}
        columns={[
          {
            key: "createdAt",
            title: "Thời điểm",
            render: (r) => date(r.createdAt),
          },
          { key: "action", title: "Thao tác" },
          { key: "actorType", title: "Nguồn" },
          { key: "entityType", title: "Đối tượng" },
          { key: "reason", title: "Lý do" },
        ]}
        onSelect={setSelected}
      />
      {selected && (
        <Modal title="Chi tiết thay đổi" close={() => setSelected(null)}>
          <h3>Trước thay đổi</h3>
          <pre>{JSON.stringify(JSON.parse(selected.beforeJson), null, 2)}</pre>
          <h3>Sau thay đổi</h3>
          <pre>{JSON.stringify(JSON.parse(selected.afterJson), null, 2)}</pre>
          <p>{selected.reason}</p>
        </Modal>
      )}
    </>
  );
}
