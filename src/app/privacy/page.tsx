import type { Metadata } from "next";

export const metadata: Metadata = { title: "Informativa privacy" };

// DRAFT (bozza): to be completed by the company with the data controller's details
// and reviewed before go-live. Fields marked [da completare] are placeholders.
export default function PrivacyPage() {
  return (
    <main className="mx-auto w-full max-w-2xl px-4 py-8 leading-relaxed">
      <h1 className="text-2xl font-semibold">Informativa privacy – Prenotazione scarico</h1>
      <p className="mt-1 text-sm text-slate-500">ai sensi dell&apos;art. 13 del Regolamento (UE) 2016/679 (GDPR)</p>

      <h2 className="mt-6 font-semibold">Titolare del trattamento</h2>
      <p>[da completare: ragione sociale, sede, contatto privacy]</p>

      <h2 className="mt-6 font-semibold">Dati trattati</h2>
      <p>
        Azienda di trasporto, e-mail di contatto e, se forniti, nome e telefono dell&apos;autista, targa del mezzo, fornitore,
        numeri d&apos;ordine e di DDT, note.
      </p>

      <h2 className="mt-6 font-semibold">Finalità e base giuridica</h2>
      <p>
        Organizzare lo scarico delle merci presso i nostri magazzini, confermare la prenotazione e gestire l&apos;accesso al
        magazzino. Base giuridica: esecuzione delle attività di consegna e legittimo interesse all&apos;organizzazione del
        ricevimento merci.
      </p>

      <h2 className="mt-6 font-semibold">Conservazione</h2>
      <p>
        I dati personali sono conservati per 12 mesi dalla data dello scarico e poi resi anonimi. I dati statistici anonimi
        possono essere conservati più a lungo.
      </p>

      <h2 className="mt-6 font-semibold">Destinatari</h2>
      <p>
        Personale dei magazzini e fornitori tecnici che operano per nostro conto: hosting presso Aruba (Italia) e servizio
        di posta elettronica per l&apos;invio delle notifiche.
      </p>

      <h2 className="mt-6 font-semibold">Diritti</h2>
      <p>
        È possibile chiedere accesso, rettifica, cancellazione o limitazione dei dati e opporsi al trattamento scrivendo a
        [da completare: indirizzo e-mail]. È inoltre possibile proporre reclamo al Garante per la protezione dei dati
        personali.
      </p>
    </main>
  );
}
