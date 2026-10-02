export type Row = Record<string, any>;
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public retryAfter?: string | null,
  ) {
    super(message);
  }
}
let csrf: { token: string; headerName: string } | null = null;
export async function api<T = Row>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const method = options.method || "GET";
  const headers = new Headers(options.headers);
  if (options.body) headers.set("Content-Type", "application/json");
  if (!["GET", "HEAD"].includes(method)) {
    if (!csrf) {
      const response = await fetch("/api/v1/auth/csrf", {
        credentials: "include",
        cache: "no-store",
      });
      if (!response.ok)
        throw new ApiError(
          response.status,
          "CSRF_UNAVAILABLE",
          "Không lấy được phiên bảo mật",
        );
      csrf = await response.json();
    }
    if (csrf) headers.set(csrf.headerName, csrf.token);
  }
  let response: Response;
  try {
    response = await fetch("/api/v1" + path, {
      ...options,
      headers,
      credentials: "include",
      cache: "no-store",
    });
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") throw error;
    throw new ApiError(0, "NETWORK_ERROR", "Không kết nối được hệ thống");
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    if (body.code === "CSRF_INVALID") csrf = null;
    throw new ApiError(
      response.status,
      body.code || "API_ERROR",
      body.message || "Yêu cầu chưa hoàn tất",
      response.headers.get("Retry-After"),
    );
  }
  if (
    path === "/auth/login" ||
    path === "/auth/logout" ||
    path === "/me/password"
  )
    csrf = null;
  return response.status === 204 ? (undefined as T) : response.json();
}
export function mutate<T = Row>(
  path: string,
  body: unknown,
  method = "POST",
  headers?: HeadersInit,
) {
  return api<T>(path, { method, body: JSON.stringify(body), headers });
}
export const date = (v: unknown) =>
  typeof v === "string"
    ? new Intl.DateTimeFormat("vi-VN", {
        dateStyle: "short",
        timeStyle: "short",
        timeZone: "Asia/Ho_Chi_Minh",
      }).format(new Date(v))
    : "—";
export const money = (v: unknown) =>
  new Intl.NumberFormat("vi-VN").format(Number(v)) + " VND";
