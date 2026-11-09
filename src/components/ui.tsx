import { useId, type ButtonHTMLAttributes, type ReactNode } from "react";

export const inputClass =
  "w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5 text-base outline-none transition-colors focus:border-blue-700 focus:ring-2 focus:ring-blue-700/20 aria-[invalid=true]:border-red-600 dark:border-slate-700 dark:bg-slate-900";

type Variant = "primary" | "secondary" | "danger";

/** Classes for a button-looking element (also used on links). */
export function buttonClass(variant: Variant = "primary", extra = "") {
  const styles = {
    primary: "bg-blue-700 text-white hover:bg-blue-800 disabled:bg-slate-300 disabled:text-slate-500 dark:disabled:bg-slate-800",
    secondary:
      "border border-slate-300 bg-white text-slate-900 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-white dark:hover:bg-slate-800",
    danger: "bg-red-700 text-white hover:bg-red-800 disabled:opacity-50",
  }[variant];
  return `inline-flex min-h-12 items-center justify-center rounded-xl px-5 font-semibold transition-colors disabled:cursor-not-allowed ${styles} ${extra}`;
}

export function Button({
  variant = "primary",
  className = "",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return <button type="button" {...props} className={buttonClass(variant, className)} />;
}

export function Field({
  label,
  hint,
  error,
  children,
}: {
  label: string;
  hint?: string;
  error?: string | null;
  children: (id: string) => ReactNode;
}) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
        {hint && <span className="font-normal text-slate-500"> · {hint}</span>}
      </label>
      {children(id)}
      {error && <p className="text-sm text-red-700 dark:text-red-400">{error}</p>}
    </div>
  );
}

export function ErrorNote({ children }: { children: ReactNode }) {
  return (
    <div role="alert" className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-red-800 dark:border-red-900 dark:bg-red-950/50 dark:text-red-200">
      {children}
    </div>
  );
}

export function StepHeader({ steps, current }: { steps: string[]; current: number }) {
  return (
    <nav aria-label="steps">
      <ol className="flex gap-1">
        {steps.map((s, i) => (
          <li key={s} className="flex-1">
            <div className={`h-1.5 rounded-full ${i <= current ? "bg-blue-700" : "bg-slate-200 dark:bg-slate-800"}`} />
          </li>
        ))}
      </ol>
      <p className="mt-2 text-sm text-slate-500" aria-current="step">
        {current + 1}/{steps.length} · {steps[current]}
      </p>
    </nav>
  );
}
