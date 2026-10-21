import { useRef, type InputHTMLAttributes, type ReactNode } from "react";
import { ApiError } from "../api/client";
export function Field({
  label,
  hint,
  error,
  ...props
}: InputHTMLAttributes<HTMLInputElement> & {
  label: string;
  hint?: string;
  error?: unknown;
}) {
  const detail =
    error instanceof ApiError
      ? error.details.find((d) => d.field === props.name)
      : undefined;
  return (
    <label className="field">
      <span>{label}</span>
      <input
        {...props}
        aria-label={label}
        aria-invalid={!!detail}
        aria-describedby={
          [
            hint ? `${props.name}-hint` : "",
            detail ? `${props.name}-error` : "",
          ]
            .filter(Boolean)
            .join(" ") || undefined
        }
      />
      {hint && <small id={`${props.name}-hint`}>{hint}</small>}
      {detail && (
        <small id={`${props.name}-error`} className="field-error">
          {detail.message}
        </small>
      )}
    </label>
  );
}
export function Textarea({
  label,
  name,
  value,
  onChange,
  rows = 5,
  hint,
}: {
  label: string;
  name: string;
  value: string;
  onChange: (v: string) => void;
  rows?: number;
  hint?: string;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      <textarea
        name={name}
        aria-label={label}
        aria-describedby={hint ? `${name}-hint` : undefined}
        rows={rows}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
      {hint && <small id={`${name}-hint`}>{hint}</small>}
    </label>
  );
}
export const text = (form: HTMLFormElement, name: string) =>
  String(new FormData(form).get(name) || "");
export function useIntentKey() {
  const intent = useRef<{ signature: string; key: string } | null>(null);
  return {
    key: (body: unknown) => {
      const signature = JSON.stringify(body);
      if (intent.current?.signature !== signature)
        intent.current = { signature, key: crypto.randomUUID() };
      return intent.current!.key;
    },
    reset: () => {
      intent.current = null;
    },
  };
}
export function passwordValid(password: string) {
  const len = Array.from(password).length;
  if (len < 10 || len > 128) throw new Error("密码需为 10–128 个字符");
}
export function FormPanel({ children }: { children: ReactNode }) {
  return <section className="form-panel">{children}</section>;
}
