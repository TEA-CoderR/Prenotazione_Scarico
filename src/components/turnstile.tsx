"use client";

import { useEffect, useRef } from "react";

// Minimal typing of the Turnstile browser API (explicit rendering).
interface TurnstileApi {
  render(
    el: HTMLElement,
    opts: {
      sitekey: string;
      callback: (token: string) => void;
      "expired-callback"?: () => void;
      "error-callback"?: () => void;
      language?: string;
      theme?: "auto" | "light" | "dark";
    },
  ): string;
  reset(id: string): void;
  remove(id: string): void;
}
declare global {
  interface Window {
    turnstile?: TurnstileApi;
  }
}

const SCRIPT_SRC = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";
let scriptPromise: Promise<TurnstileApi> | null = null;

function loadScript(): Promise<TurnstileApi> {
  if (window.turnstile) return Promise.resolve(window.turnstile);
  scriptPromise ??= new Promise((resolve, reject) => {
    const s = document.createElement("script");
    s.src = SCRIPT_SRC;
    s.async = true;
    s.onload = () => (window.turnstile ? resolve(window.turnstile) : reject(new Error("turnstile missing")));
    s.onerror = () => {
      scriptPromise = null;
      reject(new Error("turnstile script failed"));
    };
    document.head.appendChild(s);
  });
  return scriptPromise;
}

/**
 * Renders the Cloudflare Turnstile widget and reports its token (null when expired/failed).
 * Change `resetKey` to get a fresh token after each submit: tokens are single-use.
 */
export function Turnstile(props: { siteKey: string; lang: string; resetKey: number; onToken: (t: string | null) => void; onLoadError: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  const onToken = useRef(props.onToken);
  const onLoadError = useRef(props.onLoadError);
  useEffect(() => {
    onToken.current = props.onToken;
    onLoadError.current = props.onLoadError;
  });

  useEffect(() => {
    let id: string | undefined;
    let api: TurnstileApi | undefined;
    let cancelled = false;
    onToken.current(null);
    loadScript()
      .then((t) => {
        if (cancelled || !ref.current) return;
        api = t;
        id = t.render(ref.current, {
          sitekey: props.siteKey,
          language: props.lang,
          theme: "auto",
          callback: (token) => onToken.current(token),
          "expired-callback": () => onToken.current(null),
          "error-callback": () => onToken.current(null),
        });
      })
      .catch(() => !cancelled && onLoadError.current());
    return () => {
      cancelled = true;
      if (api && id) api.remove(id);
    };
  }, [props.siteKey, props.lang, props.resetKey]);

  return <div ref={ref} className="min-h-[65px]" data-testid="captcha" />;
}
