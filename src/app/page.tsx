import { BookingWizard } from "@/components/booking-wizard";
import { LangProvider, PageHeader } from "@/components/lang";

// The home page is the booking wizard: drivers land directly on step 1.
export default function Home() {
  return (
    <LangProvider>
      <PageHeader />
      <main className="mx-auto w-full max-w-2xl flex-1 px-4 pt-6">
        <BookingWizard />
      </main>
    </LangProvider>
  );
}
