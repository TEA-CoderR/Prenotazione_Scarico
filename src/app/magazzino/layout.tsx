import type { Metadata } from "next";
import Link from "next/link";
import type { ReactNode } from "react";

export const metadata: Metadata = {
  title: "Magazzino · Prenotazione scarico",
  robots: { index: false, follow: false },
};

export default function StaffLayout({ children }: { children: ReactNode }) {
  return (
    <>
      <header className="border-b border-slate-200 px-4 py-3 dark:border-slate-800">
        <Link href="/magazzino" className="font-semibold">
          Prenotazione scarico · <span className="text-blue-700 dark:text-blue-400">Magazzino</span>
        </Link>
      </header>
      <main className="mx-auto w-full max-w-3xl flex-1 px-4 py-5">{children}</main>
    </>
  );
}
