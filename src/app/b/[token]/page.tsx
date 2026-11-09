import type { Metadata } from "next";
import { Suspense } from "react";
import { LangProvider, PageHeader } from "@/components/lang";
import { ManageBooking } from "@/components/manage-booking";

export const metadata: Metadata = {
  title: "La sua prenotazione",
  // The URL is a secret link: keep it out of search engines and referrers.
  robots: { index: false, follow: false },
  referrer: "no-referrer",
};

export default function ManagePage() {
  return (
    <LangProvider>
      <PageHeader />
      <main className="mx-auto w-full max-w-2xl flex-1 px-4 py-6">
        <Suspense>
          <ManageBooking />
        </Suspense>
      </main>
    </LangProvider>
  );
}
