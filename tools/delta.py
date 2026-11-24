"""
Parches de actualización de Lobo (formato LOBODIFF1).

Un parche convierte el APK que ya está instalado en el APK nuevo, así el celular descarga solo lo que
cambió. Usa el algoritmo bsdiff, pero con compresión zlib para que la app lo aplique sin librerías extra.

Formato (enteros de 8 bytes, big-endian):
  "LOBODIFF1" | tamaño nuevo | nº de triples | bytes zlib(control) | zlib(control)
              | bytes zlib(diff) | zlib(diff) | bytes zlib(extra) | zlib(extra)
  control = triples (x, y, z): sumar x bytes de diff a lo viejo, copiar y bytes de extra,
            mover la posición en lo viejo z bytes (z puede ser negativo).

Uso:
  python3 delta.py crear viejo.apk nuevo.apk salida.patch
  python3 delta.py aplicar viejo.apk parche.patch salida.apk
"""
import struct, sys, zlib, hashlib
import bsdiff4.core as core

MAGIC = b"LOBODIFF1"

def crear(old: bytes, new: bytes) -> bytes:
    raw, bdiff, bextra = core.diff(old, new)
    # bsdiff4 entrega el control como enteros de 8 bytes "signo + magnitud" little-endian
    vals = [core.decode_int64(raw[i:i + 8]) for i in range(0, len(raw), 8)]
    control = [tuple(vals[i:i + 3]) for i in range(0, len(vals), 3)]
    ctrl = b"".join(struct.pack(">qqq", x, y, z) for x, y, z in control)
    parts = [zlib.compress(p, 9) for p in (ctrl, bdiff, bextra)]
    out = MAGIC + struct.pack(">qq", len(new), len(control))
    for p in parts:
        out += struct.pack(">q", len(p)) + p
    return out

def aplicar(old: bytes, patch: bytes) -> bytes:
    assert patch[:9] == MAGIC, "no es un parche de Lobo"
    new_size, n = struct.unpack(">qq", patch[9:25]); pos = 25; blocks = []
    for _ in range(3):
        (L,) = struct.unpack(">q", patch[pos:pos + 8]); pos += 8
        blocks.append(zlib.decompress(patch[pos:pos + L])); pos += L
    ctrl, diff, extra = blocks
    new = bytearray(new_size); op = np_ = dp = ep = 0
    for i in range(n):
        x, y, z = struct.unpack(">qqq", ctrl[i * 24:(i + 1) * 24])
        for k in range(x):
            o = old[op + k] if 0 <= op + k < len(old) else 0
            new[np_ + k] = (diff[dp + k] + o) & 0xFF
        np_ += x; op += x; dp += x
        new[np_:np_ + y] = extra[ep:ep + y]; np_ += y; ep += y
        op += z
    return bytes(new)

if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "crear":
        old, new = open(sys.argv[2], "rb").read(), open(sys.argv[3], "rb").read()
        p = crear(old, new)
        assert aplicar(old, p) == new, "el parche no reconstruye el APK nuevo"
        open(sys.argv[4], "wb").write(p)
        print(f"parche {len(p)/1e6:.2f} MB (APK completo {len(new)/1e6:.2f} MB)")
    elif cmd == "aplicar":
        old, p = open(sys.argv[2], "rb").read(), open(sys.argv[3], "rb").read()
        open(sys.argv[4], "wb").write(aplicar(old, p))
