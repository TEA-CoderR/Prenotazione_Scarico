/** ?from=YYYY-MM-DD&to=YYYY-MM-DD&site=<id|all> → report query input. */
export function reportQuery(q: URLSearchParams) {
  const site = q.get("site");
  return { from: q.get("from") ?? "", to: q.get("to") ?? "", siteId: !site || site === "all" ? null : Number(site) };
}
