// GET /app → the latest store scanner app (APK built by .github/workflows/scanner.yml).
// Short address to type in the browser of the Zebra terminal: <site>/app
export function GET() {
  return Response.redirect("https://github.com/TEA-CoderR/Prenotazione_Scarico/releases/download/scanner-latest/casate-scanner.apk", 302);
}
