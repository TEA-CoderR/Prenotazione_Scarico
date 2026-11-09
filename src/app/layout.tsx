import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Prenotazione scarico",
  description: "Prenotazione online degli orari di scarico merci",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="it" className="h-full antialiased">
      <body className="flex min-h-full flex-col">{children}</body>
    </html>
  );
}
