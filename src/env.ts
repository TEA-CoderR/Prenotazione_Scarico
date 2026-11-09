import { z } from "zod";

const schema = z.object({
  DATABASE_URL: z.string().min(1),
  APP_URL: z.url().default("http://localhost:3000"),
  // Aliyun enterprise mail (阿里云企业邮箱). Leave SMTP_USER empty in development:
  // mails are then printed to the console instead of being sent.
  SMTP_HOST: z.string().default("smtp.qiye.aliyun.com"),
  SMTP_PORT: z.coerce.number().int().default(465),
  SMTP_USER: z.string().optional(),
  SMTP_PASS: z.string().optional(),
  MAIL_FROM: z.string().default("Prenotazione Scarico <noreply@casa-te.com>"),
  // Cloudflare Turnstile (captcha on the booking form). Both empty = no captcha (development).
  TURNSTILE_SITE_KEY: z.string().optional(),
  TURNSTILE_SECRET_KEY: z.string().optional(),
});

export type Env = z.infer<typeof schema>;

let cached: Env | undefined;

export function env(): Env {
  cached ??= schema.parse(process.env);
  return cached;
}
