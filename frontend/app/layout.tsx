import type { Metadata, Viewport } from "next";
import localFont from "next/font/local";

import { AdminViewProvider } from "@/components/AdminViewProvider";
import { AuthProvider } from "@/components/AuthProvider";
import { OfflineAppShell } from "@/components/OfflineAppShell";
import { ThemeProvider } from "@/components/ThemeProvider";
import { ToastProvider } from "@/components/ui/Toast";
import { PREFERENCES_BOOT_SCRIPT, THEME_COLOR } from "@/lib/preferences";
import "./globals.css";

const inter = localFont({
  src: "../fonts/inter-latin-var.woff2",
  display: "swap",
  variable: "--font-inter",
  weight: "100 900"
});

const jakarta = localFont({
  src: "../fonts/jakarta-latin-var.woff2",
  display: "swap",
  variable: "--font-jakarta",
  weight: "200 800"
});

export const metadata: Metadata = {
  title: {
    default: "Design Prototype Workshop",
    template: "%s · Design Prototype Workshop"
  },
  description:
    "Capture every stage of a craft and textile design & prototype development workshop, then generate the report — text, tables and images — as .docx or PDF."
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: THEME_COLOR.light },
    { media: "(prefers-color-scheme: dark)", color: THEME_COLOR.dark }
  ]
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html className={`${inter.variable} ${jakarta.variable}`} lang="en" suppressHydrationWarning>
      <body className="font-sans">
        <script dangerouslySetInnerHTML={{ __html: PREFERENCES_BOOT_SCRIPT }} />
        <OfflineAppShell />
        <AuthProvider>
          <ThemeProvider>
            <ToastProvider>
              <AdminViewProvider>{children}</AdminViewProvider>
            </ToastProvider>
          </ThemeProvider>
        </AuthProvider>
      </body>
    </html>
  );
}
