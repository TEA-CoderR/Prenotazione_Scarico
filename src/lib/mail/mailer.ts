import nodemailer, { type Transporter } from "nodemailer";
import { env } from "@/env";

export interface OutgoingMail {
  to: string;
  subject: string;
  text: string;
  html: string;
}

export interface Mailer {
  send(mail: OutgoingMail): Promise<void>;
}

/** Prints mails instead of sending them (development, or SMTP not configured). */
export const consoleMailer: Mailer = {
  async send(mail) {
    console.info(`[mail] to=${mail.to} subject="${mail.subject}"\n${mail.text}`);
  },
};

let transporter: Transporter | undefined;

/** Aliyun enterprise mail over SMTPS (smtp.qiye.aliyun.com:465) when credentials are set. */
export function getMailer(): Mailer {
  const e = env();
  if (!e.SMTP_USER || !e.SMTP_PASS) return consoleMailer;
  transporter ??= nodemailer.createTransport({
    host: e.SMTP_HOST,
    port: e.SMTP_PORT,
    secure: e.SMTP_PORT === 465,
    auth: { user: e.SMTP_USER, pass: e.SMTP_PASS },
  });
  const t = transporter;
  return {
    async send(mail) {
      await t.sendMail({ from: e.MAIL_FROM, ...mail });
    },
  };
}
