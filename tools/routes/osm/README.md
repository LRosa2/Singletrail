# OpenStreetMap MTB route packages

Builds a Singletrail **route package**, a zip of GPX files that the app imports in one go, from
mountain bike routes mapped in OpenStreetMap. It's meant for signposted local routes that aren't part
of SchweizMobil's network: the default picks the routes run by Appenzellerland Tourismus around
St. Gallen and Appenzell, which the [SchweizMobil packages](../schweizmobil/README.md) don't have.

Each route relation whose `operator` matches becomes one GPX, named for the app's search:

- `Kaien-Route · Heiden loop`
- `Route Kamor – Ruhesitz · Steinegg loop`

The description gives the operator, whether it's a loop signposted in one direction, where it
starts, the places it passes, and the signposted distance, climb and time.

## How a route becomes a track

- The relation's ways are joined in member order. Each way is ridden only from where the route comes
  onto it to where it leaves, because a route often uses just part of a longer way.
- A way listed out of place is moved into the gap it fills.
- Gaps over 50 m skip the route, with a note. Smaller gaps are bridged with a straight line.
- Signposted routes run in member order. Where the relation lists `via` places, the track is checked
  against them and turned around if it passes them backwards. The output says `ok`, `reversed` or
  `unclear` (no places to check).
- Heights come from swisstopo's 2 m terrain model (swissALTI3D), sampled every 10 m and put on
  the track's points. So it only works inside Switzerland and Liechtenstein.

## Build a package

```bash
python -I tools/routes/osm/export_routes.py out/
```

Options:
- `--box S,W,N,E`: where to look for route relations, in degrees. The default covers St. Gallen and
  Appenzell.
- `--operator REGEX`: the operators to take. The default is `Appenzellerland Tourismus`.
- `--package NAME`: the name of the zip and of the route list `NAME.md`. Importing the zip makes a
  collection of that name.
- `--refresh`: fetch the routes from OpenStreetMap again instead of using `out/osm.json`.

The script uses only the Python standard library (3.10+). It reads the routes from the Overpass
API and the heights from the federal profile service (`api3.geo.admin.ch`), and caches both in
`out/`.

## Licences

- **Routes:** © OpenStreetMap contributors, under the
  [ODbL 1.0](https://www.openstreetmap.org/copyright). A package is a database derived from
  OpenStreetMap, so it is offered under the ODbL 1.0 too.
- **Heights:** swisstopo, open use with the source given.

Every GPX names both.

## Use a package

Packages are published as GitHub releases named `routes-…`. In Singletrail, either:
- **Import from link…** with the zip's download link (app 0.1.1 or newer), or
- download the zip on the phone, then **Import** and pick it.
