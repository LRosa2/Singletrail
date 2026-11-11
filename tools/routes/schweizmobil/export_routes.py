"""
Builds a Singletrail route package from SchweizMobil's open MTB data ("Mountainbikeland Schweiz",
opendata.swiss: open use, the source must be given): one GPX per local route and per stage of the
national and regional routes that lie in the chosen cantons, zipped for one import.

    python -I export_routes.py mountainbikeland_2056.gpkg out/ [--cantons SG,TG,AR,AI,GL,SH,FL]
                               [--package SchweizMobil-MTB-Ostschweiz] [--box E1,N1,E2,N2]

Data: https://data.geo.admin.ch/ch.astra.mountainbikeland/mountainbikeland/mountainbikeland_2056.gpkg
Where each route lies is looked up with the federal canton layer (api3.geo.admin.ch) at a few
points per route; the answers are cached in out/canton-cache.json.
"""
import argparse
import json
import re
import sqlite3
import sys
import time
import unicodedata
import urllib.request
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

sys.path.insert(0, str(Path(__file__).parent))
from gpkg import lv95_to_wgs84, parts  # noqa: E402

CANTONS = {
    "AG": "Aargau", "AI": "Appenzell Innerrhoden", "AR": "Appenzell Ausserrhoden", "BE": "Bern",
    "BL": "Basel-Landschaft", "BS": "Basel-Stadt", "FR": "Fribourg", "GE": "Geneva", "GL": "Glarus",
    "GR": "Graubünden", "JU": "Jura", "LU": "Lucerne", "NE": "Neuchâtel", "NW": "Nidwalden",
    "OW": "Obwalden", "SG": "St. Gallen", "SH": "Schaffhausen", "SO": "Solothurn", "SZ": "Schwyz",
    "TG": "Thurgau", "TI": "Ticino", "UR": "Uri", "VD": "Vaud", "VS": "Valais", "ZG": "Zug",
    "ZH": "Zürich", "FL": "Liechtenstein",
}
# Liechtenstein, which the Swiss canton layer doesn't cover (WGS84 box).
FL_BOX = (47.048, 9.471, 47.271, 9.636)
SAMPLES = 9
SOURCE = "Source: SchweizMobil, Mountainbikeland Schweiz (opendata.swiss, open use)."
LEVEL = {"easy": "easy", "medium": "medium", "difficult": "difficult"}


class Cantons:
    """Which canton a point (LV95) is in, from the federal API, cached on disk."""

    def __init__(self, cache_file: Path):
        self.file = cache_file
        self.cache = json.loads(cache_file.read_text()) if cache_file.exists() else {}

    def at(self, e, n):
        key = f"{round(e, -1):.0f},{round(n, -1):.0f}"
        if key not in self.cache:
            url = (
                "https://api3.geo.admin.ch/rest/services/api/MapServer/identify?geometryType=esriGeometryPoint"
                f"&geometry={e:.0f},{n:.0f}&layers=all:ch.swisstopo.swissboundaries3d-kanton-flaeche.fill"
                "&sr=2056&tolerance=0&returnGeometry=false"
            )
            req = urllib.request.Request(url, headers={"User-Agent": "singletrail-route-export/1.0"})
            with urllib.request.urlopen(req, timeout=30) as r:
                results = json.load(r).get("results", [])
            time.sleep(0.15)  # be gentle with the public API
            if results:
                self.cache[key] = results[0]["attributes"]["ak"]
            else:
                lat, lon = lv95_to_wgs84(e, n)
                inside = FL_BOX[0] <= lat <= FL_BOX[2] and FL_BOX[1] <= lon <= FL_BOX[3]
                self.cache[key] = "FL" if inside else "-"
        return self.cache[key]

    def save(self):
        self.file.write_text(json.dumps(self.cache))


def joined(blob):
    """The geometry's parts joined in riding order; None if they don't join up (gap over 50 m)."""
    out = []
    for p in parts(blob):
        if out:
            gap = ((out[-1][0] - p[0][0]) ** 2 + (out[-1][1] - p[0][1]) ** 2) ** 0.5
            if gap > 50:
                return None
            if gap < 0.5:
                p = p[1:]
        out.extend(p)
    return out


def slug(text):
    for a, b in (("ä", "ae"), ("ö", "oe"), ("ü", "ue"), ("Ä", "Ae"), ("Ö", "Oe"), ("Ü", "Ue")):
        text = text.replace(a, b)
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    return re.sub(r"[^A-Za-z0-9]+", "-", text).strip("-")


def places(a, z, back):
    """"Uznach loop" or "Steg → Wattwil"; the return direction runs the other way."""
    a, z = (a or "").strip(), (z or "").strip()
    if back:
        a, z = z, a
    if a and (a == z or z.startswith(a + ",") or a.startswith(z + ",")):
        return f"{a} loop"
    return f"{a} → {z}" if a and z else a or z


def direction(back, where):
    """The signposted return direction: "back" from B to A, or a loop "reversed"."""
    if not back:
        return ""
    return " reversed" if where.endswith(" loop") else " back"


def canton_names(found):
    seen = [c for c in found if c in CANTONS]
    order = sorted(set(seen), key=lambda c: -seen.count(c))
    names = [CANTONS[c] for c in order]
    if not names:
        return ""
    return ("Canton " if len(names) == 1 else "Cantons ") + ", ".join(names) + "."


def gpx(name, desc, pts):
    rows = []
    for e, n, *z in pts:
        lat, lon = lv95_to_wgs84(e, n)
        ele = f"<ele>{z[0]:.1f}</ele>" if z else ""
        rows.append(f'<trkpt lat="{lat:.6f}" lon="{lon:.6f}">{ele}</trkpt>')
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<gpx version="1.1" creator="Singletrail tools/routes/schweizmobil" xmlns="http://www.topografix.com/GPX/1/1">\n'
        f"<metadata><name>{escape(name)}</name><desc>{escape(desc)}</desc></metadata>\n"
        f"<trk><name>{escape(name)}</name><desc>{escape(desc)}</desc><trkseg>\n"
        + "\n".join(rows)
        + "\n</trkseg></trk>\n</gpx>\n"
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("gpkg")
    ap.add_argument("out")
    ap.add_argument("--cantons", default="SG,TG,AR,AI,GL,SH,FL", help="codes; FL is Liechtenstein")
    ap.add_argument("--package", default="SchweizMobil-MTB-Ostschweiz")
    ap.add_argument("--share", type=float, default=1 / 3, help="part of a route that must lie in the cantons")
    ap.add_argument(
        "--box", default="2670000,1170000,2800000,1300000",
        help="LV95 min E,min N,max E,max N around the cantons (default: eastern Switzerland and Liechtenstein)",
    )
    args = ap.parse_args()
    target = set(args.cantons.upper().split(","))
    box = [float(v) for v in args.box.split(",")]
    out = Path(args.out)
    gpx_dir = out / args.package
    gpx_dir.mkdir(parents=True, exist_ok=True)
    for old in gpx_dir.glob("*.gpx"):
        old.unlink()
    cantons = Cantons(out / "canton-cache.json")
    cur = sqlite3.connect(f"file:{args.gpkg}?mode=ro", uri=True).cursor()

    routes = {}
    for nr, name, typ in cur.execute("SELECT NrR, NameR, Typ_TR FROM Route"):
        routes.setdefault(nr, (name, typ))

    items = []
    for nr, name, a, z, km, up, down, fit, tech, abw, richtung, blob in cur.execute(
        "SELECT NrR, NameR, AOrt, ZOrt, LaengeR, HoeheAufR, HoeheAbR, KonditionR, TechnikR, Abwicklung, Richtung, geom "
        "FROM Route WHERE Typ_TR = 'local'"
    ):
        back = abw == "Rückweg"
        where = places(a, z, back)
        title = f"{name} ({nr}){direction(back, where)} · {where}"
        oneway = richtung and richtung.startswith("nur")
        what = f"SchweizMobil local mountain bike route {nr} {name}{', return direction' if back else ''}"
        items.append(("local", title, what, where, km, up, down, fit, tech, oneway, blob))
    for nr, stage, a, z, km, up, down, fit, tech, abw, blob in cur.execute(
        "SELECT NrR, NrEtappe, NameS, NameZ, DistanzE, HoeheAufE, HoeheAbE, KonditionE, TechnikE, Abwicklung, geom FROM Etappe"
    ):
        name, typ = routes.get(nr, ("", ""))
        # Local routes repeat here as "stage 0"; a few unnamed link pieces have no route at all.
        if typ not in ("national", "regional"):
            continue
        back = abw == "Rückweg"
        where = places(a, z, back)
        part = f" stage {stage}" if stage else ""
        title = f"{name} ({nr}){part}{direction(back, where)} · {where}"
        what = f"SchweizMobil {typ} mountain bike route {nr} {name}{part}{', return direction' if back else ''}"
        if back:  # climbing and descending swap too
            up, down = down, up
        items.append((typ, title, what, where, km, up, down, fit, tech, False, blob))

    written, skipped, index = [], [], []
    for typ, title, what, where, km, up, down, fit, tech, oneway, blob in items:
        pts = joined(blob)
        first = pts if pts is not None else parts(blob)[0]
        xs = [p[0] for p in first]
        ys = [p[1] for p in first]
        # Only routes that reach into the box are looked up, to keep the API calls few.
        if max(xs) < box[0] or min(xs) > box[2] or max(ys) < box[1] or min(ys) > box[3]:
            continue
        picks = [first[round(i * (len(first) - 1) / (SAMPLES - 1))] for i in range(SAMPLES)]
        found = [cantons.at(p[0], p[1]) for p in picks]
        if sum(c in target for c in found) < args.share * len(found):
            continue
        if pts is None:
            skipped.append(f"{title}: its pieces don't join up")
            continue
        desc = (
            f"{what}. {where}, {km} km, up {up} m, down {down} m. "
            f"Fitness {LEVEL.get(fit or '', 'not given')}, technique {LEVEL.get(tech or '', 'not given')}. "
            f"{canton_names(found)}"
            + (" Signposted in one direction, start to finish." if oneway else "")
            + f" {SOURCE}"
        )
        file = gpx_dir / f"{slug(title)}.gpx"
        file.write_text(gpx(title, desc, pts), encoding="utf-8")
        written.append(file)
        index.append((typ, title, km, up, LEVEL.get(fit or "", "-"), LEVEL.get(tech or "", "-")))
        cantons.save()
    cantons.save()

    zip_path = out / f"{args.package}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in sorted(written):
            zf.write(f, f.name)
    order = {"national": 0, "regional": 1, "local": 2}
    index.sort(key=lambda r: (order.get(r[0], 3), r[1]))
    lines = ["| Level | Route | km | Climb m | Fitness | Technique |", "|---|---|---|---|---|---|"]
    lines += [f"| {t} | {title} | {km} | {up} | {fit} | {tech} |" for t, title, km, up, fit, tech in index]
    (out / f"{args.package}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    counts = {t: sum(1 for r in index if r[0] == t) for t in order}
    print(f"{len(written)} routes {counts} -> {zip_path} ({zip_path.stat().st_size / 1e6:.1f} MB)")
    for s in skipped:
        print("skipped:", s)


main()
