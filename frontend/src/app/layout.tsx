import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = {
  title: "MAIN — Bãi đỗ thông minh",
  description: "Quản lý thẻ, chỗ đỗ và gia hạn trong một không gian.",
};
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="vi">
      <body>{children}</body>
    </html>
  );
}
