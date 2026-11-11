# SchweizMobil MTB route packages

Builds a Singletrail **route package**, a zip of GPX files that the app imports in one go. It's made
from SchweizMobil's official mountain bike network ("Mountainbikeland Schweiz"), which the
SwitzerlandMobility Foundation publishes as Swiss open government data. Terms: open use, the source
must be given; every GPX names it.

Each package holds one GPX per **local route** and per **stage** of the national and regional
routes that lie at least one third in the chosen cantons. Names are ready for the app's search:

- `Farner Bike (481) · Uznach loop`
- `Hörnli Bike (33) stage 2 · Hulftegg → Hemberg`
- `Hörnli Bike (33) stage 1 back · Hulftegg → Winterthur`: the signposted return direction (`reversed` for a loop)

Each description holds the level, places, distance, climb and descent, fitness and technique, the
cantons the route crosses, and whether it is signposted in one direction only.

## Build a package

```bash
curl -O https://data.geo.admin.ch/ch.astra.mountainbikeland/mountainbikeland/mountainbikeland_2056.gpkg
python -I tools/routes/schweizmobil/export_routes.py mountainbikeland_2056.gpkg out/
```

Options:
- `--cantons SG,TG,AR,AI,GL,SH,FL`: the cantons wanted (FL is Liechtenstein). The default is eastern Switzerland without Graubünden.
- `--box E1,N1,E2,N2`: an LV95 box around those cantons. Only routes reaching into it are looked up.
- `--package NAME`: the name of the zip and of the route list `NAME.md`.

The script uses only the Python standard library (3.10+). It finds the cantons with the federal
geodata API (`api3.geo.admin.ch`) and caches the answers in `out/canton-cache.json`. Coordinates are
converted from LV95 with swisstopo's approximate formulas, which are accurate to about 1 m.

## Use a package

Packages are published as GitHub releases named `routes-…`. In Singletrail, either:
- **Import from link…** with the zip's download link (app 0.1.1 or newer), or
- download the zip on the phone, then **Import** and pick it.
