"""Minimal GeoPackage geometry reader and Swiss LV95 -> WGS84 conversion (no GDAL needed)."""
import struct


def parts(blob):
    """Line parts of a GeoPackage LINESTRING / MULTILINESTRING (with or without Z), as lists of (E, N[, z])."""
    flags = blob[3]
    envelope = {0: 0, 1: 32, 2: 48, 3: 48, 4: 64}[(flags >> 1) & 0x07]
    wkb = memoryview(blob)[8 + envelope:]
    out = []

    def read(off):
        order = "<" if wkb[off] == 1 else ">"
        gtype = struct.unpack_from(order + "I", wkb, off + 1)[0]
        if gtype & 0x80000000:  # EWKB-style Z flag
            base, dims = gtype & 0xFFFF, 3
        else:  # ISO WKB: 1000s = Z, 2000s = M, 3000s = ZM
            base, dims = gtype % 1000, {0: 2, 1: 3, 2: 3, 3: 4}[gtype // 1000]
        off += 5
        if base == 2:
            n = struct.unpack_from(order + "I", wkb, off)[0]
            off += 4
            out.append([struct.unpack_from(order + "d" * dims, wkb, off + i * 8 * dims) for i in range(n)])
            return off + n * 8 * dims
        if base == 5:
            n = struct.unpack_from(order + "I", wkb, off)[0]
            off += 4
            for _ in range(n):
                off = read(off)
            return off
        raise ValueError(f"unsupported geometry type {gtype}")

    read(0)
    return out


def lv95_to_wgs84(e, n):
    """swisstopo's approximate formulas (about 1 m accuracy): LV95 easting/northing -> (lat, lon)."""
    y = (e - 2_600_000) / 1_000_000
    x = (n - 1_200_000) / 1_000_000
    lon = 2.6779094 + 4.728982 * y + 0.791484 * y * x + 0.1306 * y * x * x - 0.0436 * y ** 3
    lat = 16.9023892 + 3.238272 * x - 0.270978 * y * y - 0.002528 * x * x - 0.0447 * y * y * x - 0.0140 * x ** 3
    return lat * 100 / 36, lon * 100 / 36
