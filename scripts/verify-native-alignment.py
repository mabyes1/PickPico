"""Check the shipped ARM64 ELF segments and APK storage alignment (stdlib only)."""

import argparse
import struct
import sys
import zipfile

PAGE_SIZE = 16384


def relro_overlaps_writable_data(headers, start, size):
    # Bionic rounds RELRO outwards to pages. An unaligned end is safe only
    # when the extra protected bytes are padding, not another writable LOAD.
    # https://android.googlesource.com/platform/bionic/+/refs/heads/main/linker/linker_phdr.cpp
    end = start + size
    padding = ((start // PAGE_SIZE * PAGE_SIZE, start),
               (end, (end + PAGE_SIZE - 1) // PAGE_SIZE * PAGE_SIZE))
    for kind, flags, _, address, _, _, memsize, _ in headers:
        if kind == 1 and flags & 2:  # writable PT_LOAD
            for left, right in padding:
                if max(left, address) < min(right, address + memsize):
                    return True
    return False


def verify(apk):
    errors = []
    checked = 0
    with zipfile.ZipFile(apk) as archive, open(apk, "rb") as raw:
        for entry in archive.infolist():
            if not (entry.filename.startswith("lib/arm64-v8a/") and entry.filename.endswith(".so")):
                continue
            checked += 1
            data = archive.read(entry)
            if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
                errors.append(f"{entry.filename}: expected little-endian ELF64")
                continue
            phoff = struct.unpack_from("<Q", data, 32)[0]
            phsize, phcount = struct.unpack_from("<HH", data, 54)
            if phsize < 56 or phoff + phsize * phcount > len(data):
                errors.append(f"{entry.filename}: invalid program header table")
                continue
            headers = [struct.unpack_from("<IIQQQQQQ", data, phoff + index * phsize)
                       for index in range(phcount)]
            loads = 0
            for index, header in enumerate(headers):
                kind, _, offset, vaddr, _, _, memsize, alignment = header
                if kind == 1:  # PT_LOAD
                    loads += 1
                    if alignment < PAGE_SIZE or (vaddr - offset) % PAGE_SIZE:
                        errors.append(f"{entry.filename}: LOAD {index} is not 16 KB aligned")
                elif kind == 0x6474E552:  # PT_GNU_RELRO
                    if relro_overlaps_writable_data(headers, vaddr, memsize):
                        errors.append(f"{entry.filename}: rounded RELRO would protect writable data")
            if not loads:
                errors.append(f"{entry.filename}: no LOAD segments")
            if entry.compress_type == zipfile.ZIP_STORED:
                raw.seek(entry.header_offset + 26)
                name_length, extra_length = struct.unpack("<HH", raw.read(4))
                if (entry.header_offset + 30 + name_length + extra_length) % PAGE_SIZE:
                    errors.append(f"{entry.filename}: uncompressed APK entry is not 16 KB aligned")
            print(f"Checked {entry.filename}: {loads} LOAD segments")
    if not checked:
        errors.append("APK contains no ARM64 native libraries")
    for error in errors:
        print(f"FAIL: {error}", file=sys.stderr)
    if not errors:
        print(f"PASS: all {checked} ARM64 libraries pass 16 KB LOAD, RELRO safety and APK alignment checks")
    return 1 if errors else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    args = parser.parse_args()
    sys.exit(verify(args.apk))
