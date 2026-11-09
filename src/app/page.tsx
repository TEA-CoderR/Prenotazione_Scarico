// Placeholder home page. The booking wizard, warehouse board and admin area come next.
const ENTITIES = ["BMS", "FOIANO", "MARLIA", "SAN FILIPPO", "SAN MARCO", "SAN CONCORDIO"];

export default function Home() {
  return (
    <main className="mx-auto flex min-h-screen max-w-xl flex-col justify-center gap-6 px-4 py-16">
      <h1 className="text-3xl font-semibold tracking-tight">Prenotazione scarico merci</h1>
      <p className="text-base text-neutral-600 dark:text-neutral-400">
        Prenota online l&apos;orario di scarico presso i nostri magazzini. Il servizio è in fase di attivazione.
      </p>
      <ul className="grid grid-cols-2 gap-2 text-sm">
        {ENTITIES.map((name, code) => (
          <li key={name} className="rounded-lg border border-neutral-200 px-3 py-2 dark:border-neutral-800">
            <span className="text-neutral-500">{code}</span> {name}
          </li>
        ))}
      </ul>
      <p className="text-sm text-neutral-500">Lunedì–venerdì, 9:00–18:00</p>
    </main>
  );
}
