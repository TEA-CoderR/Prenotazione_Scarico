"use client";

import Link from "next/link";
import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { LANGS, messages, type Lang, type Messages } from "@/lib/i18n/messages";

const KEY = "lang";

const LangContext = createContext<{ lang: Lang; t: Messages; setLang: (l: Lang) => void }>({
  lang: "it",
  t: messages.it,
  setLang: () => {},
});

function readStored(): Lang | null {
  try {
    const v = localStorage.getItem(KEY);
    return v === "it" || v === "en" ? v : null;
  } catch {
    return null;
  }
}

/** Italian by default; English for non-Italian browsers; the visitor's choice is remembered. */
export function LangProvider({ children, fallback }: { children: ReactNode; fallback?: Lang }) {
  const [lang, setLangState] = useState<Lang>("it");

  useEffect(() => {
    const stored = readStored();
    const browser = navigator.language?.toLowerCase().startsWith("it") ? "it" : "en";
    // Syncing with browser-only state after hydration (server always renders Italian).
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLangState(stored ?? fallback ?? browser);
  }, [fallback]);

  useEffect(() => {
    document.documentElement.lang = lang;
  }, [lang]);

  const setLang = (l: Lang) => {
    setLangState(l);
    try {
      localStorage.setItem(KEY, l);
    } catch {
      /* private mode: not remembered */
    }
  };

  return <LangContext.Provider value={{ lang, t: messages[lang], setLang }}>{children}</LangContext.Provider>;
}

export function useLang() {
  return useContext(LangContext);
}

export function LangSwitch() {
  const { lang, setLang } = useLang();
  return (
    <div className="flex rounded-full border border-slate-300 p-0.5 text-sm dark:border-slate-700" role="group" aria-label="Lingua / Language">
      {LANGS.map((l) => (
        <button
          key={l}
          type="button"
          onClick={() => setLang(l)}
          aria-pressed={lang === l}
          className={`rounded-full px-3 py-1 font-medium uppercase transition-colors ${
            lang === l ? "bg-slate-900 text-white dark:bg-white dark:text-slate-900" : "text-slate-600 dark:text-slate-300"
          }`}
        >
          {l}
        </button>
      ))}
    </div>
  );
}

export function PageHeader() {
  const { t } = useLang();
  return (
    <header className="flex items-center justify-between gap-4 border-b border-slate-200 px-4 py-3 dark:border-slate-800">
      <Link href="/" className="flex items-center gap-2 font-semibold text-slate-900 dark:text-white">
        <span aria-hidden className="grid h-8 w-8 place-items-center rounded-lg bg-blue-700 text-white">
          <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M3 7h11v9H3zM14 10h4l3 3v3h-7z" />
            <circle cx="7" cy="17.5" r="1.5" />
            <circle cx="17" cy="17.5" r="1.5" />
          </svg>
        </span>
        <span>{t.appTitle}</span>
      </Link>
      <LangSwitch />
    </header>
  );
}
