"""
Builds a Singletrail route package from mountain bike routes mapped in OpenStreetMap: one GPX per
route relation whose operator matches, with heights from swisstopo, zipped for one import.

    python -I export_routes.py out/ [--box S,W,N,E] [--operator REGEX] [--package NAME] [--refresh]

OpenStreetMap data: (c) OpenStreetMap contributors, ODbL 1.0 (https://www.openstreetmap.org/copyright).
The package is a database derived from it and is offered under the ODbL 1.0 too.
Heights: swisstopo's terrain model (swissALTI3D, 2 m) through the federal profile service; open use,
the source must be given. Every GPX names both.

The relations come from the Overpass API and the heights from api3.geo.admin.ch; both answers are
cached in out/ (osm.json, places.json, height-cache.json), so a second run works offline. --refresh
fetches the routes again.
"""
import argparse
import hashlib
import json
import math
import re
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

OVERPASS = ["https://overpass.kumi.systems/api/interpreter", "https://overpass-api.de/api/interpreter"]
PROFILE = "https://api3.geo.admin.ch/rest/services/profile.json"
AGENT = {"User-Agent": "singletrail-route-export/1.0"}
SOURCE = (
    "Route: (c) OpenStreetMap contributors, ODbL 1.0 (openstreetmap.org/copyright). "
    "Heights: swisstopo swissALTI3D."
)
# Ways of a route meet at shared nodes; anything farther apart than this is a gap in the mapping.
JOIN_M = 2.0
MAX_GAP_M = 50.0
# Heights are sampled about this far apart along the route, then put on its own points.
SAMPLE_M = 10.0
MAX_SAMPLES = 5000


def post(url, fields, timeout):
    req = urllib.request.Request(url, data=urllib.parse.urlencode(fields).encode(), headers=AGENT)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def overpass(query, cache, refresh):
    if cache.exists() and not refresh:
        return json.loads(cache.read_text(encoding="utf-8"))
    for url in OVERPASS:
        try:
            data = post(url, {"data": query}, 240)
            cache.write_text(json.dumps(data), encoding="utf-8")
            return data
        except Exception as e:  # noqa: BLE001 - try the next server
            print(f"overpass: {url} failed: {e}", file=sys.stderr)
    sys.exit("overpass: no server answered; try again later")


def metres(a, b):
    """Distance between two (lat, lon) points."""
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * 6371008.8 * math.asin(math.sqrt(h))


def touch(a, b):
    """
    Where ways a and b meet, as (index in a, index in b, metres apart). One of the two points is an
    end of its way; the other may be in the middle, where a route turns onto a longer way.
    """
    best = (0, 0, math.inf)
    for i in (0, len(a) - 1):
        for j, q in enumerate(b):
            d = metres(a[i], q)
            if d < best[2]:
                best = (i, j, d)
    for j in (0, len(b) - 1):
        for i, p in enumerate(a):
            d = metres(p, b[j])
            if d < best[2]:
                best = (i, j, d)
    return best


def ordered(segs):
    """The member order, with a way listed out of place moved into the gap it fills."""
    segs = list(segs)
    for i in range(len(segs) - 1):
        if touch(segs[i], segs[i + 1])[2] <= JOIN_M:
            continue
        for j in range(i + 2, len(segs)):
            if touch(segs[i], segs[j])[2] <= JOIN_M and touch(segs[j], segs[i + 1])[2] <= JOIN_M:
                segs.insert(i + 1, segs.pop(j))
                break
    return segs


def chain(ways):
    """
    The relation's ways joined into one line in member order. Each way is ridden from where the
    route comes onto it to where it leaves, so the unused ends of longer ways are left out.
    Returns the points and the gaps (in metres) where the mapping doesn't join up.
    """
    segs = ordered([[(p["lat"], p["lon"]) for p in w] for w in ways if w])
    if not segs:
        return [], []
    joints = [touch(a, b) for a, b in zip(segs, segs[1:])]
    gaps = [d for _, _, d in joints if d > JOIN_M]
    line = []
    for k, seg in enumerate(segs):
        last = len(seg) - 1
        leave = joints[k][0] if k < len(segs) - 1 else None
        enter = joints[k - 1][1] if k > 0 else None
        if enter is None:  # the first way starts at its end away from where the route leaves it
            enter = last if leave is not None and leave <= last / 2 else 0
        if leave is None:  # the last way ends where the line began (a loop) or at its far end
            back = min(range(len(seg)), key=lambda i: metres(seg[i], line[0])) if line else None
            if back is not None and back != enter and metres(seg[back], line[0]) <= JOIN_M:
                leave = back
            else:
                leave = last if enter <= last / 2 else 0
        piece = seg[enter:leave + 1] if enter <= leave else seg[leave:enter + 1][::-1]
        if line and metres(line[-1], piece[0]) <= JOIN_M:
            piece = piece[1:]
        line.extend(piece)
    return line, gaps


def length(pts):
    return sum(metres(a, b) for a, b in zip(pts, pts[1:]))


def wgs84_to_lv95(lat, lon):
    """swisstopo's approximate formulas, accurate to about 1 m."""
    p = (lat * 3600 - 169028.66) / 10000
    l = (lon * 3600 - 26782.5) / 10000
    e = 2600072.37 + 211455.93 * l - 10938.51 * l * p - 0.36 * l * p * p - 44.54 * l ** 3
    n = 1200147.07 + 308807.95 * p + 3745.25 * l * l + 76.63 * p * p - 194.56 * l * l * p + 119.79 * p ** 3
    return e, n


def heights(pts, cache):
    """A height for every point: the route is sampled about every SAMPLE_M, then interpolated."""
    key = hashlib.sha1(json.dumps(pts).encode()).hexdigest()
    if key not in cache:
        lv = [wgs84_to_lv95(*p) for p in pts]
        geom = json.dumps({"type": "LineString", "coordinates": [[round(e, 1), round(n, 1)] for e, n in lv]})
        total = sum(math.dist(a, b) for a, b in zip(lv, lv[1:]))
        n = max(2, min(MAX_SAMPLES, math.ceil(total / SAMPLE_M)))
        rows = post(PROFILE, {"geom": geom, "sr": "2056", "nb_points": str(n)}, 120)
        time.sleep(0.5)  # be gentle with the public API
        samples = [(r["dist"], r["alts"]["DTM2"]) for r in rows if r["alts"].get("DTM2") is not None]
        # Each point's distance along the route, in the same metres the profile counts.
        along, d = [0.0], 0.0
        for a, b in zip(lv, lv[1:]):
            d += math.dist(a, b)
            along.append(d)
        out, j = [], 0
        for d in along:
            while j < len(samples) - 2 and samples[j + 1][0] < d:
                j += 1
            (d0, h0), (d1, h1) = samples[j], samples[j + 1]
            t = 0.0 if d1 == d0 else min(1.0, max(0.0, (d - d0) / (d1 - d0)))
            out.append(round(h0 + t * (h1 - h0), 1))
        cache[key] = out
    return cache[key]


def climb(eles):
    """Metres up, counting only rises of 3 m or more so that the terrain model's noise doesn't add up."""
    up, low = 0.0, eles[0]
    for h in eles[1:]:
        if h < low:
            low = h
        elif h - low >= 3:
            up += h - low
            low = h
    return up


def place(name):
    """"Heiden Bahnhof" → "Heiden"; "Appenzell P Brauerei" → "Appenzell"."""
    return re.sub(r"\s+(Bahnhof|Dorf|Bushaltestelle|P\s.*)$", "", (name or "").strip())


def nearest_index(pts, at):
    return min(range(len(pts)), key=lambda i: metres(pts[i], at))


def via_order(pts, vias, spots):
    """
    Whether the line passes the signposted via places in their order: "ok", "reversed", or
    "unclear" when too few of them were found or they don't agree.
    """
    found = [nearest_index(pts, spots[v]) for v in vias if v in spots and min(metres(p, spots[v]) for p in pts[::5]) < 1500]
    if len(found) < 2:
        return "unclear"
    rising = sum(b > a for a, b in zip(found, found[1:]))
    falling = sum(b < a for a, b in zip(found, found[1:]))
    if rising and not falling:
        return "ok"
    if falling and not rising:
        return "reversed"
    return "unclear"


def slug(text):
    for a, b in (("ä", "ae"), ("ö", "oe"), ("ü", "ue"), ("Ä", "Ae"), ("Ö", "Oe"), ("Ü", "Ue")):
        text = text.replace(a, b)
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    return re.sub(r"[^A-Za-z0-9]+", "-", text).strip("-")


def gpx(name, desc, pts, eles):
    rows = [f'<trkpt lat="{la:.6f}" lon="{lo:.6f}"><ele>{h:.1f}</ele></trkpt>' for (la, lo), h in zip(pts, eles)]
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<gpx version="1.1" creator="Singletrail tools/routes/osm" xmlns="http://www.topografix.com/GPX/1/1">\n'
        f"<metadata><name>{escape(name)}</name><desc>{escape(desc)}</desc>"
        '<copyright author="OpenStreetMap contributors"><license>https://opendatacommons.org/licenses/odbl/1-0/</license></copyright>'
        "</metadata>\n"
        f"<trk><name>{escape(name)}</name><desc>{escape(desc)}</desc><trkseg>\n"
        + "\n".join(rows)
        + "\n</trkseg></trk>\n</gpx>\n"
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--box", default="47.33,9.20,47.55,9.55", help="south,west,north,east (default: around St. Gallen and Appenzell)")
    ap.add_argument("--operator", default="Appenzellerland Tourismus", help="regex the route's operator must match")
    ap.add_argument("--package", default="Appenzellerland-MTB")
    ap.add_argument("--refresh", action="store_true", help="fetch the routes from OpenStreetMap again")
    args = ap.parse_args()
    out = Path(args.out)
    gpx_dir = out / args.package
    gpx_dir.mkdir(parents=True, exist_ok=True)
    for old in gpx_dir.glob("*.gpx"):
        old.unlink()

    data = overpass(f'[out:json][timeout:180];relation["route"="mtb"]({args.box});out geom;', out / "osm.json", args.refresh)
    wanted = re.compile(args.operator)
    rels = [r for r in data["elements"] if wanted.search(r.get("tags", {}).get("operator", ""))]
    rels.sort(key=lambda r: r["tags"].get("name", ""))

    # Where the via places are, to check each route runs in its signposted direction.
    names = set()
    for r in rels:
        names.update(v.strip() for v in r["tags"].get("via", "").split(";") if v.strip())
    spots = {}
    if names:
        pattern = "|".join(re.escape(n) for n in sorted(names))
        # Routes reach beyond the box they were found in, so the places are looked for around them.
        lats = [p["lat"] for r in rels for m in r["members"] for p in m.get("geometry") or []]
        lons = [p["lon"] for r in rels for m in r["members"] for p in m.get("geometry") or []]
        around = f"{min(lats) - 0.02:.3f},{min(lons) - 0.02:.3f},{max(lats) + 0.02:.3f},{max(lons) + 0.02:.3f}"
        found = overpass(f'[out:json][timeout:120];node["name"~"^({pattern})$"]({around});out;', out / "places.json", args.refresh)
        for el in found["elements"]:
            # A village or hamlet beats a bus stop or a restaurant of the same name.
            if el["tags"]["name"] not in spots or "place" in el["tags"] or "natural" in el["tags"]:
                spots[el["tags"]["name"]] = (el["lat"], el["lon"])

    height_file = out / "height-cache.json"
    height_cache = json.loads(height_file.read_text()) if height_file.exists() else {}
    written, index, problems = [], [], []
    for r in rels:
        t = r["tags"]
        name = t.get("name", f"Route {r['id']}")
        pts, gaps = chain([m.get("geometry") for m in r["members"] if m["type"] == "way"])
        big = [g for g in gaps if g > MAX_GAP_M]
        if big:
            problems.append(f"{name}: skipped, gaps of {', '.join(f'{g:.0f}' for g in big)} m")
            continue
        loop = t.get("roundtrip") == "yes" or (t.get("from") and t.get("from") == t.get("to"))
        vias = [v.strip() for v in t.get("via", "").split(";") if v.strip()]
        order = via_order(pts, vias, spots)
        if order == "reversed":
            pts.reverse()
        ends = metres(pts[0], pts[-1])
        if loop and ends > 200:
            problems.append(f"{name}: a loop whose ends are {ends:.0f} m apart")
        eles = heights(pts, height_cache)
        height_file.write_text(json.dumps(height_cache))
        km = length(pts) / 1000
        up = climb(eles)
        start = place(t.get("from"))
        where = f"{start} loop" if loop else f"{start} → {place(t.get('to'))}"
        title = f"{name} · {where}"
        signed = []
        if t.get("distance"):
            signed.append(f"{t['distance']} km")
        if t.get("ascent"):
            signed.append(f"up {t['ascent']} m")
        if t.get("duration"):
            signed.append(f"about {t['duration']} h")
        desc = (
            f"{t.get('operator', 'Local')} mountain bike route"
            + (", a loop" if loop else "")
            + (" signposted in one direction" if t.get("oneway") == "yes" else "")
            + f". Starts at {t.get('from')}"
            + (f", via {', '.join(vias)}" if vias else "")
            + (f", ends at {t.get('to')}" if not loop and t.get("to") else "")
            + "."
            + (f" Signposted: {', '.join(signed)}." if signed else "")
            + f" {SOURCE}"
        )
        file = gpx_dir / f"{slug(title)}.gpx"
        file.write_text(gpx(title, desc, pts, eles), encoding="utf-8")
        written.append(file)
        index.append((title, t.get("distance", "-"), t.get("ascent", "-"), f"{km:.1f}", f"{up:.0f}", order, r["id"]))
        print(
            f"{title}: {km:.1f} km (signposted {t.get('distance', '-')}), up {up:.0f} m (signposted {t.get('ascent', '-')}), "
            f"{len(pts)} points, joins {len(gaps)} small gaps, direction by via places: {order}"
        )

    zip_path = out / f"{args.package}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in sorted(written):
            zf.write(f, f.name)
    lines = [
        "| Route | km | Climb m | OSM relation |",
        "|---|---|---|---|",
    ]
    lines += [f"| {title} | {km} | {up} | [{rid}](https://www.openstreetmap.org/relation/{rid}) |" for title, _, _, km, up, _, rid in index]
    lines += ["", "km and climb are measured on the GPX (climb from swisstopo heights).", "", SOURCE]
    (out / f"{args.package}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{len(written)} routes -> {zip_path} ({zip_path.stat().st_size / 1e6:.2f} MB)")
    for p in problems:
        print("problem:", p)


main()
