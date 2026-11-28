"""
Pictures of the scanner's «Novità e guida» (what changed in the update and how to use it).

Each picture is a faithful mock-up of the real screen (same colours, sizes and words as the app) with numbered
markers; the app shows it with the numbered explanations (Guide.kt). Redraw after a change of the screens:

    python mobile/guide/screens.py        → mobile/app/src/main/res/drawable-nodpi/guide_*.webp

Needs Python playwright (Chromium) and Pillow.
"""
import io
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable-nodpi")

CSS = """
*{box-sizing:border-box;margin:0;padding:0}
body{width:360px;height:700px;overflow:hidden;background:#F3F5F9;font-family:Roboto,'Noto Sans',Arial,sans-serif;color:#0B1220;font-size:13px;line-height:1.3;position:relative}
.nav{background:#0B1220;color:#fff;padding:12px 14px;display:flex;align-items:center;gap:10px}
.back{width:34px;height:34px;border-radius:10px;background:#16213A;display:grid;place-items:center;font-size:20px;color:#fff;flex:none}
.t{font-weight:700;font-size:17px}.s{font-size:12px;color:#9AA6BF}
.grow{flex:1;min-width:0}
.pill{font-size:12px;font-weight:700;padding:5px 10px;border-radius:999px;background:#4A3A10;color:#FFC857;white-space:nowrap}
.sec{display:flex;justify-content:space-between;padding:9px 16px 6px;font-size:12px;letter-spacing:.06em;font-weight:700;color:#5B6478}
.sec.am{color:#7A5300;background:#FFF8E6}
.ln{display:flex;gap:10px;align-items:center;padding:8px 10px 8px 14px;background:#fff;border-bottom:1px solid #E1E6EF}
.ph{width:42px;height:42px;border-radius:9px;background:#EEF2F8;border:1px solid #E1E6EF;flex:none;display:grid;place-items:center;font-weight:700;font-size:11px;color:#5B6478}
.ph.g{box-shadow:inset 3px 0 0 #0E9F6E}.ph.o{box-shadow:inset 3px 0 0 #C2410C}.ph.b{box-shadow:inset 3px 0 0 #2F6BFF}
.mid{flex:1;min-width:0}
.nm{font-weight:600;font-size:14px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.sb{font-size:12px;color:#5B6478;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-top:2px}
.am{color:#7A5300;font-weight:600}.gr{color:#0B6B4B;font-weight:700}.bl{color:#1D4FD8;font-weight:700}.or{color:#C2410C;font-weight:700}
.mono{font-family:'Roboto Mono',monospace}
.step{display:flex;align-items:center;border:1px solid #D5DBE6;border-radius:10px;height:32px;flex:none;background:#fff;width:108px;justify-content:space-between}
.step span{width:30px;text-align:center;color:#2F6BFF;font-weight:700;font-size:17px}.step b{font-family:'Roboto Mono',monospace;font-size:14px}
.foot{position:absolute;left:0;right:0;bottom:0;background:#fff;padding:10px 14px 12px;border-top:1px solid #E1E6EF}
.btn{border-radius:14px;height:48px;display:flex;align-items:center;justify-content:center;gap:8px;font-weight:700;font-size:15px;color:#fff;background:#2F6BFF}
.btn.green{background:#0E9F6E}
.card{background:#fff;border:1px solid #E1E6EF;border-radius:16px;margin:10px 14px;padding:12px 14px}
.cap{font-size:11px;letter-spacing:.06em;font-weight:700;color:#5B6478;display:flex;justify-content:space-between;margin-bottom:8px}
.big{font-family:'Roboto Mono',monospace;font-weight:700;font-size:30px}
.kv{display:flex;gap:6px;margin-top:10px}.kv>div{flex:1;background:#F3F5F9;border-radius:10px;padding:6px 8px}
.kv .l{font-size:11px;color:#5B6478}.kv .v{font-family:'Roboto Mono',monospace;font-weight:700;font-size:15px}
.row{display:flex;justify-content:space-between;padding:4px 0;font-size:14px}
.tl{display:flex;gap:10px;padding:8px 0;border-bottom:1px solid #E1E6EF;align-items:center}.tl:last-child{border:0}
.bars{display:flex;align-items:flex-end;gap:4px;height:70px}.bars i{flex:1;background:#C9D7FF;border-radius:3px 3px 0 0;display:block}.bars i.cur{background:#2F6BFF}
.mon{display:flex;gap:4px;font-size:10px;color:#8A93A6;margin-top:3px}.mon span{flex:1;text-align:center}
.bub{max-width:80%;border-radius:16px;padding:8px 12px;font-size:14px;margin:6px 12px}
.me{margin-left:auto;background:#2F6BFF;color:#fff}.of{background:#fff;border:1px solid #E1E6EF}
.bub .w{font-size:11px;opacity:.75;margin-top:3px}
.chips{display:flex;gap:6px;overflow:hidden;padding:0 14px}
.chip{border:1px solid #D5DBE6;background:#fff;border-radius:999px;padding:7px 12px;font-weight:600;font-size:13px;white-space:nowrap}
.field{border:2px solid #2F6BFF;border-radius:14px;padding:10px 12px;min-height:58px;font-size:15px;margin:8px 14px 0}
.tools{display:flex;gap:8px;align-items:center;padding:10px 14px}
.tool{border:1px solid #D5DBE6;border-radius:12px;height:42px;padding:0 12px;display:flex;align-items:center;gap:6px;font-weight:700;font-size:13px;background:#fff}
.send{margin-left:auto;background:#2F6BFF;color:#fff;border-radius:14px;height:42px;padding:0 18px;display:flex;align-items:center;font-weight:700;font-size:15px}
.seg{display:flex;background:#fff;border-bottom:1px solid #E1E6EF}
.seg div{flex:1;text-align:center;padding:10px 2px 8px;font-weight:600;color:#5B6478;font-size:13px;border-bottom:3px solid transparent}
.seg .on{color:#0B1220;border-color:#2F6BFF;font-weight:700}
.addb{background:#2F6BFF;color:#fff;border-radius:9px;padding:7px 10px;font-weight:700;font-size:12px}
.kbd{position:absolute;left:0;right:0;bottom:0;height:210px;background:#D3D8E0;display:grid;place-items:center;color:#7A8394;font-size:12px}
.hero{padding:16px;color:#fff}
.mk{position:absolute;width:30px;height:30px;border-radius:50%;background:#F5A524;color:#0B1220;font-weight:800;font-size:16px;
    display:grid;place-items:center;border:3px solid #fff;box-shadow:0 2px 8px rgba(11,18,32,.35);z-index:9}
.ring{position:absolute;border:3px solid #F5A524;border-radius:14px;z-index:8;box-shadow:0 0 0 4px rgba(245,165,36,.18)}
"""


def mk(n, x, y):
    return f'<div class="mk" style="left:{x}px;top:{y}px">{n}</div>'


def ring(x, y, w, h, r=14):
    return f'<div class="ring" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px;border-radius:{r}px"></div>'


def ln(ph, cls, name, sub, sub_cls="", right="", mk_=None):
    at = f' data-mk="{mk_}"' if mk_ else ""
    return (f'<div class="ln"{at}><div class="ph {cls}">{ph}</div><div class="mid"><div class="nm">{name}</div>'
            f'<div class="sb {sub_cls}">{sub}</div></div>{right}</div>')


STEP = lambda n: f'<div class="step"><span>−</span><b>{n}</b><span>+</span></div>'

SCREENS = {}

# 1 · search by name
SCREENS["guide_search"] = (
    '<div class="nav" style="padding:10px 12px"><div data-mk="1" style="flex:1;display:flex;align-items:center;gap:8px;background:#fff;'
    'border-radius:12px;height:44px;padding:0 6px 0 12px;color:#0B1220"><span style="color:#8A93A6;font-size:18px">⌕</span>'
    '<span style="flex:1;font-weight:600;font-size:15px">svelto</span><span style="color:#8A93A6">✕</span>'
    '<span class="addb" style="padding:9px 14px;font-size:14px">Cerca</span></div></div>'
    '<div class="sec">7 ARTICOLI</div><div style="margin:0 14px;border:1px solid #E1E6EF;border-radius:14px;overflow:hidden">'
    + ln("SV", "g", "SVELTO PIATTI LIMONE 1000 ML", "● Ordinabile", "gr", mk_=2)
    + ln("SV", "g", "SVELTO PIATTI ACETO 1000 ML X 3 PZ", "● Ordinabile", "gr")
    + ln("SV", "b", "SVELTO PIATTI 980 ML X 3 PZ", "● Sostituito da SVELTO 1000 ML x 3", "bl")
    + ln("SV", "o", "SVELTO GEL LAVASTOVIGLIE 30 LAV", "● Non in Griglia · solo urgenze", "or")
    + '</div>'
)

# 2 · list in sections
SCREENS["guide_list"] = (
    '<div class="nav"><div class="back">‹</div><div class="grow"><div class="t">Lista sett. 48</div>'
    '<div class="s">38 articoli · 74 colli · chiude dom</div></div><span class="pill">2 da contare</span></div>'
    '<div class="sec am" data-mk="1"><span>DA CONTROLLARE · 2</span><span>⌄</span></div>'
    + ln("SV", "g", "SVELTO PIATTI LIMONE 1000 ML", "Consigliati 4 · ne metti 40", "am", STEP(40))
    + ln("DX", "g", "DIXAN LAV. 40 MISURINI", "Già in arrivo 2 colli", "am", STEP(2))
    + '<div class="sec" data-mk="2"><span>GRIGLIA · 30</span><span>74 colli ⌄</span></div>'
    + ln("CC", "g", "COCCOLINO DILUITO 50 LAV", "4 pz/collo · 8 pz", "", STEP(2))
    + ln("LY", "g", "LYSOFORM CLASSICO 40 LAV", "6 pz/collo · 6 pz", "", STEP(1))
    + ln("ML", "g", "MASTROLINDO LIMONE 1000 ML", "12 pz/collo · 36 pz", "", STEP(3))
    + '<div class="sec"><span>NON IN GRIGLIA · 3</span><span>›</span></div>'
    + '<div class="sec"><span>SOSTITUTI · 2</span><span>›</span></div>'
    + '<div class="foot"><div class="btn" data-mk="3">➤ Invia la lista · 74 colli</div></div>'
)

# 3 · ask the office
SCREENS["guide_ask"] = (
    '<div style="position:absolute;inset:0;background:rgba(11,18,32,.45)"></div>'
    '<div style="position:absolute;left:8px;right:8px;bottom:8px;background:#fff;border-radius:24px;overflow:hidden">'
    '<div style="display:flex;gap:10px;align-items:center;padding:14px 12px 10px 16px;border-bottom:1px solid #E1E6EF">'
    '<div class="ph">NI</div><div class="mid"><div class="nm">NICKY MAGIA NATALE 4 ROT.</div>'
    '<div class="sb" style="font-weight:700">Chiedi all\'ufficio acquisti</div></div><div style="font-size:18px;color:#5B6478">✕</div></div>'
    '<div style="padding:10px 0 0"><div class="chips" data-mk="1"><div class="chip" style="white-space:normal;border-radius:14px">C\'è un articolo che lo sostituisce o tolgo questo prodotto dallo scaffale?</div>'
    '</div></div>'
    '<div class="field" data-mk="2">C\'è un articolo che lo sostituisce o tolgo questo prodotto dallo scaffale?<span style="color:#2F6BFF">|</span></div>'
    '<div class="tools" data-mk="3"><div class="tool">▣ 2 colli</div><div class="tool">◉ Foto</div><div class="send">Invia ➤</div></div></div>'
)

# 4 · requests list + conversation
SCREENS["guide_requests"] = (
    '<div class="nav"><div class="back">‹</div><div class="grow"><div class="t">NICKY MAGIA NATALE 4 ROT.</div>'
    '<div class="s" style="color:#FFC857">L\'ufficio ti ha chiesto qualcosa</div></div></div>'
    '<div style="padding:8px 0" data-mk="1">'
    '<div class="bub me">Finito da 2 settimane, i clienti lo chiedono<div class="w">Tu · 25/11 10:12 · 2 colli</div></div>'
    '<div class="bub of" style="background:#FFF4DB;border-color:#F5D78E">È il formato nuovo da 6 rotoli? Mandaci una foto dell\'etichetta.<div class="w">Ufficio acquisti · 25/11 15:40</div></div>'
    '<div class="bub me">📷 Foto · sì, da 6<div class="w">Tu · 26/11 09:02</div></div>'
    '<div class="bub of" style="background:#DCF7EC;border-color:#DCF7EC">Sì, in lista: 2 colli da MELONI.<div class="w">Ufficio acquisti · 26/11 11:15</div></div>'
    '</div>'
    '<div style="position:absolute;left:0;right:0;bottom:0;background:#fff;border-top:1px solid #E1E6EF;padding:10px 14px">'
    '<div class="sec" style="padding:0 0 8px">DALLA HOME</div>'
    '<div data-mk="2" style="display:flex;gap:10px;align-items:center;border:1px solid #F5D78E;background:#FFF4DB;border-radius:16px;padding:10px 12px">'
    '<div style="width:34px;height:34px;border-radius:10px;background:#F5A524;display:grid;place-items:center;color:#fff;font-weight:800">?</div>'
    '<div class="mid"><div style="font-weight:700;font-size:14px">Richieste all\'ufficio</div>'
    '<div class="sb am">1 risposta da leggere · 2 in attesa</div></div><span style="color:#8A93A6">›</span></div></div>'
)

# 5 · In negozio
SCREENS["guide_instore"] = (
    '<div class="nav"><div class="back">‹</div><div class="grow"><div class="t">In negozio · FOIANO</div>'
    '<div class="s">DIXAN LAV. 40 MISURINI</div></div></div>'
    '<div class="card" data-mk="1"><div class="cap"><span>GIACENZA</span><span style="font-weight:500">dati del 28/11</span></div>'
    '<div><span class="big">6</span> <span style="color:#5B6478">pz · bastano 9 giorni</span></div>'
    '<div class="kv"><div><div class="l">Venduti 30 gg</div><div class="v">20</div></div><div><div class="l">Ultima vendita</div>'
    '<div class="v">ieri</div></div><div><div class="l">In arrivo</div><div class="v" style="color:#2F6BFF">2 colli</div></div></div></div>'
    '<div class="card" data-mk="2"><div class="cap"><span>CONTROLLA LA GIACENZA</span></div>'
    '<div class="row"><span style="color:#5B6478">Arrivati il 12/11/26 · MELONI</span><b class="gr">+24</b></div>'
    '<div class="row"><span style="color:#5B6478">Venduti da allora</span><b>−18</b></div>'
    '<div class="row" style="border-top:1px solid #E1E6EF;margin-top:4px;padding-top:6px"><b>A sistema adesso</b><b>6 pz</b></div>'
    '<div style="border:1px solid #C6D5FF;color:#1D4FD8;border-radius:12px;text-align:center;padding:10px;font-weight:700;margin-top:8px">Conta e segnala la giacenza</div></div>'
    '<div class="card" data-mk="3"><div class="cap"><span>ARRIVI</span><span style="font-weight:500">ultimi 12 mesi</span></div>'
    '<div class="tl"><span class="mono" style="width:70px">12/11/26</span><span class="grow">MELONI<br><small style="color:#5B6478">Fornitore</small></span><b class="gr">+24 pz</b></div>'
    '<div class="tl"><span class="mono" style="width:70px">15/10/26</span><span class="grow">S.MARCO<br><small style="color:#5B6478">Trasferimento</small></span><b class="gr">+6 pz</b></div></div>'
    '<div class="card" data-mk="4"><div class="cap"><span>VENDUTI AL MESE</span><span style="font-weight:500">media 21 pz</span></div>'
    '<div class="bars"><i style="height:40%"></i><i style="height:52%"></i><i style="height:38%"></i><i style="height:61%"></i>'
    '<i style="height:48%"></i><i style="height:70%"></i><i style="height:95%"></i><i style="height:55%"></i><i style="height:44%"></i>'
    '<i style="height:58%"></i><i style="height:63%"></i><i class="cur" style="height:50%"></i></div>'
    '<div class="mon"><span>D</span><span>G</span><span>F</span><span>M</span><span>A</span><span>M</span><span>G</span><span>L</span>'
    '<span>A</span><span>S</span><span>O</span><span>N</span></div></div>'
)

# 6 · substitute: numbers of the old item too
SCREENS["guide_substitute"] = (
    '<div class="nav"><div class="back">‹</div><div class="t" style="font-size:15px">FOIANO <span style="color:#9AA6BF;font-weight:400">· sett. 48</span></div></div>'
    '<div class="hero" style="background:#2F6BFF"><div style="font-size:26px;font-weight:800;letter-spacing:.02em">SOSTITUITO</div>'
    '<div style="opacity:.9;font-size:13px">Questo codice non c\'è più · ordina il nuovo</div></div>'
    '<div style="padding:14px 18px">'
    '<div data-mk="1" style="border:1.5px dashed #C4CCDA;background:#EEF1F6;border-radius:16px;padding:10px 12px;display:flex;gap:12px;align-items:center">'
    '<div class="ph" style="opacity:.6">SV</div><div class="mid"><div style="font-size:10px;font-weight:700;color:#5B6478">HAI SCANSIONATO · NON PIÙ IN VENDITA</div>'
    '<div style="text-decoration:line-through;color:#5B6478;font-weight:600;margin-top:3px">SVELTO PIATTI 980 ML X 3 PZ</div>'
    '<div class="sb mono" style="margin-top:2px">8720181389917 · 4 pz/collo</div>'
    '<div style="font-size:12px;font-weight:600;margin-top:4px">In negozio 9 pz · venduti 30g 14 · ultima vendita ieri</div></div><span style="color:#5B6478">›</span></div>'
    '<div style="text-align:center;color:#2F6BFF;font-size:18px;margin:6px 0">↓</div>'
    '<div data-mk="2" style="display:flex;gap:12px;align-items:center"><div class="ph b" style="width:64px;height:64px">SV</div><div class="mid">'
    '<div style="font-size:10px;font-weight:700;color:#2F6BFF">ORDINA QUESTO AL SUO POSTO</div>'
    '<div style="font-weight:700;font-size:16px">SVELTO PIATTI 1000 ML X 3 PZ</div><div class="sb mono">8721497047805 · 4 pz/collo</div></div></div>'
    '<div class="kv" style="margin-top:14px"><div><div class="l">In negozio</div><div class="v">0</div></div><div><div class="l">Venduti 30g</div>'
    '<div class="v">0</div></div><div><div class="l">In arrivo</div><div class="v">0</div></div><div><div class="l">Basta per</div><div class="v">–</div></div></div>'
    '</div><div class="foot"><div class="btn">Ordina il sostituto · 2 colli</div></div>'
)

# 7 · VOLANTINO
SCREENS["guide_volantino"] = (
    '<div class="nav"><div class="back">‹</div><div class="grow"><div class="t">VOLANTINO 03/12 – 20/12</div>'
    '<div class="s" style="color:#FFC857">239 articoli · 18 giorni · inizia tra 6 gg</div></div></div>'
    '<div class="seg"><div class="on" data-mk="1">Da preparare 41</div><div>Coperti 186</div><div>In lista 12</div></div>'
    + ln("DX", "g", "DIXAN LAV. 40 MISURINI", "Offerta €5,99 · OTT 26: 48 pz venduti", "",
         '<div style="text-align:right" data-mk="2"><div class="mono bl">+2 colli</div><div class="addb" style="margin-top:4px">Aggiungi</div></div>')
    + ln("SV", "g", "SVELTO PIATTI LIMONE 1000 ML", "Offerta €1,49 · OTT 26: 70 pz venduti", "",
         '<div style="text-align:right"><div class="mono bl">+4 colli</div><div class="addb" style="margin-top:4px">Aggiungi</div></div>')
    + ln("LE", "o", "LENOR UNSTOPPABLES 210 G", "Mai in volantino · stima", "or",
         '<div style="text-align:right"><div class="mono gr">2 colli</div><div class="gr" style="font-size:11px">✓ In lista</div></div>')
    + ln("AC", "g", "ACE CANDEGGINA 2 L", "Offerta €1,29 · OTT 26: 22 pz venduti", "",
         '<div style="text-align:right"><div class="mono bl">+1 collo</div><div class="addb" style="margin-top:4px">Aggiungi</div></div>')
    + '<div style="padding:12px 16px;color:#8A93A6;font-size:11px;text-align:center">Solo gli articoli che si ordinano nella lista DETERSIVI · stima dai volantini passati</div>'
    + '<div class="foot"><div class="btn" data-mk="3">＋ Aggiungi i 38 consigliati · 92 colli</div></div>'
)


# a ring around every [data-mk] element and its number on the top-right corner (kept inside the screen)
MARK_JS = """() => {
  for (const el of document.querySelectorAll('[data-mk]')) {
    const b = el.getBoundingClientRect(), pad = 4;
    const x = Math.max(2, b.left - pad), y = Math.max(2, b.top - pad);
    const w = Math.min(356 - x, b.width + 2 * pad), h = Math.min(696 - y, b.height + 2 * pad);
    const ring = document.createElement('div'); ring.className = 'ring';
    Object.assign(ring.style, {left: x + 'px', top: y + 'px', width: w + 'px', height: h + 'px'});
    const m = document.createElement('div'); m.className = 'mk'; m.textContent = el.dataset.mk;
    Object.assign(m.style, {left: Math.min(326, x + w - 22) + 'px', top: Math.max(2, y - 12) + 'px'});
    document.body.append(ring, m);
  }
}"""


def render(names=None):
    from playwright.sync_api import sync_playwright
    os.makedirs(OUT, exist_ok=True)
    with sync_playwright() as p:
        b = p.chromium.launch()
        pg = b.new_page(viewport={"width": 360, "height": 700}, device_scale_factor=2)
        for name, html in SCREENS.items():
            if names and name not in names:
                continue
            pg.set_content(f"<html><head><meta charset='utf-8'><style>{CSS}</style></head><body>{html}</body></html>")
            pg.evaluate(MARK_JS)
            png = pg.screenshot()
            im = Image.open(io.BytesIO(png)).convert("RGB")
            path = os.path.join(OUT, name + ".webp")
            im.save(path, "WEBP", quality=82, method=6)
            print(name, im.size, os.path.getsize(path) // 1024, "KB")
        b.close()


if __name__ == "__main__":
    render(sys.argv[1:] or None)
