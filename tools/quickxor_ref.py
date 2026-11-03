"""Independent pure-Python port of Microsoft's QuickXorHash, used to generate
known-answer vectors for the Kotlin implementation (app/src/test).

Spec: https://learn.microsoft.com/en-us/onedrive/developer/code-snippets/quickxorhash
Cross-checked against the PyPI C-extension `quickxorhash` (independent implementation).

Usage: python tools/quickxor_ref.py
Writes docs/quickxor-vectors.txt.
"""
import hashlib
import random
import sys

WIDTH_BITS = 160
WIDTH_BYTES = 20
SHIFT = 11
BITS_LAST_CELL = 32


def _rotate_left(block, n):
    """block: bytearray(20) little-endian bit block; rotate left by n bits."""
    n %= WIDTH_BITS
    if n == 0:
        return block
    val = int.from_bytes(block, "little")
    mask = (1 << WIDTH_BITS) - 1
    val = ((val << n) | (val >> (WIDTH_BITS - n))) & mask
    return bytearray(val.to_bytes(WIDTH_BYTES, "little"))


def quickxor(data: bytes) -> bytes:
    """Straight port of the reference: xor(rotate(extend8(b), i*11)) over bytes, reversed,
    then xor with the 64-bit length in the 8 least-significant bytes."""
    block = bytearray(WIDTH_BYTES)
    for i, b in enumerate(data):
        e = bytearray(WIDTH_BYTES)
        e[0] = b
        r = _rotate_left(e, i * SHIFT)
        for k in range(WIDTH_BYTES):
            block[k] ^= r[k]
    # The 160-bit block is little-endian; the length (LE 64-bit) xors into bits 96..159.
    length = len(data).to_bytes(8, "little")
    out = bytearray(block)
    for k in range(8):
        out[WIDTH_BYTES - 8 + k] ^= length[k]
    return bytes(out)


def c_module_hash(data: bytes) -> bytes:
    import quickxorhash

    h = quickxorhash.quickxorhash()
    h.update(data)
    return bytes(h.digest())


def main() -> int:
    rng = random.Random(1234)
    cases = {
        "empty": b"",
        "one_byte_00": b"\x00",
        "one_byte_61": b"a",
        "hello_world": b"hello world",
        "pangram": b"The quick brown fox jumps over the lazy dog",
        "160_bytes": bytes(range(160)),
        "161_bytes": bytes(range(161)) + b"x",
        "random_4k": bytes(rng.randrange(256) for _ in range(4096)),
        "zeros_10k": b"\x00" * 10000,
        "seven_bytes": b"abcdefg",
    }

    ok = True
    lines = []
    for name, data in cases.items():
        mine = quickxor(data)
        ref = c_module_hash(data)
        match = mine == ref
        ok &= match
        print(f"{name:14s} len={len(data):6d}  {mine.hex()}  match={match}")
        lines.append(f"{name}\t{len(data)}\t{data.hex() if len(data) <= 64 else ''}\t{mine.hex()}")

    if not ok:
        print("MISMATCH against reference implementation", file=sys.stderr)
        return 1

    with open("docs/quickxor-vectors.txt", "w", encoding="utf-8") as f:
        f.write("# name\tsize\thex_input (only if len<=64)\tquickXorHash (hex, 20 bytes)\n")
        f.write("\n".join(lines) + "\n")
    print("OK - vectors written to docs/quickxor-vectors.txt")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
