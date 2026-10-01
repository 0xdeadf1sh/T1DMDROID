#!/usr/bin/env python3
"""Desktop table-extraction tool for T1DMDROID (PLAN_T1DMDROID.md §9).

Carves the Libre 3 white-box runtime tables out of the user's own vendor APK
and writes them to a gitignored output dir together with manifest.json.

Usage:
    python3 extract.py <apk> [-o OUTDIR] [--kit KITDIR]
    python3 extract.py --verify-only [-o OUTDIR]

Fallback chain per region (§9 2e), every decision logged:
  1. README-documented offsets (the kit README names the same LIB offsets the
     filenames carry, e.g. fold window LIB+0x2feb18, 8 KB region at 0x274000).
  2. Offsets embedded in the kit filenames, tried as file offsets and as ELF
     virtual addresses (translated via program headers), in every native lib
     under lib/*abi*/ (liblibre3extension.so preferred).
  3. Byte-pattern search anchored on the kit file's first 32 bytes across every
     native lib; each hit verified byte-for-byte against the kit file. Multiple
     exact hits are disambiguated by the drift deltas observed on unambiguous
     regions; hits that do not verify are recorded as divergence (fail-closed:
     divergent bytes are never written).
  4. Skip with an explicit error.

phone_cert_162b.bin / phone_cert_firstpair.bin are located by byte-exact
search across every APK member. If absent, reported absent — never synthesized.

No table bytes are ever printed; only sizes, hashes and offsets.
"""

import argparse
import hashlib
import json
import os
import re
import struct
import sys
import zipfile

# §12.3 published sizes (byte-verified against the kit bundle; 57 files,
# 5,261,699 B total; the two phone certs are part of the published count).
EXPECTED = {
    "bit_mask_table_lib_1267e0.bin": 16,
    "bytecode_lib_b25d20.bin": 413696,
    "child23_71fb38_vm_desc_d84770.bin": 324816,
    "child23_71fb38_vm_start_d843d0.bin": 1000,
    "child23_program_region_435cf0.bin": 279808,
    "child23_static_copy_code_71fca0.bin": 4904,
    "child23_static_table_71e870.bin": 2120,
    "child23_ttable_b_ext_976ea8_100000.bin": 1048576,
    "cipher_fn_wbaes_T1_lib200f18.bin": 16896,
    "cipher_fn_wbaes_T2_lib202633.bin": 50688,
    "cipher_fn_wbaes_T3_lib21220d.bin": 16896,
    "cipher_fn_wbaes_T4_lib21a1c1.bin": 16992,
    "cipher_fn_wbaes_T5_lib221c92.bin": 1560,
    "decode_table_lib_237dcc.bin": 65536,
    "firstpair_633fa8_null_nibble_303a14.bin": 64,
    "firstpair_633fa8_null_tables_2fd1f1.bin": 5135,
    "firstpair_633fa8_tail_fold_tables_2fe798.bin": 896,
    "firstpair_633fa8_tail_u32_low_tables_112528.bin": 64,
    "firstpair_6388f0_caller_loop_interleaved_2cdfa9.bin": 10384,
    "firstpair_6388f0_lane_tables_302678.bin": 4680,
    "firstpair_6388f0_low_loop_statics_2fe600.bin": 404,
    "firstpair_6388f0_low_seed_statics_2f4d28.bin": 798,
    "firstpair_6388f0_selector_add_119788.bin": 32,
    "firstpair_6388f0_selector_mul_116968.bin": 32,
    "firstpair_6388f0_shared_context_2cdae1.bin": 1312,
    "firstpair_63c278_fold_tables_2feb18.bin": 15200,
    "firstpair_63c278_u32_tables_112588.bin": 83856,
    "firstpair_679f48_seed_tables_37075e.bin": 1746,
    "firstpair_df80_round_tables_37120e.bin": 1170,
    "firstpair_finalizer_tables_370e30.bin": 4818,
    "firstpair_final_len_tables_372102.bin": 1536,
    "firstpair_process2_public_tables_3038c0.bin": 1304,
    "firstpair_prog_638840_2f5046.bin": 33280,
    "firstpair_prog_64e2b8_3041b4.bin": 592,
    "firstpair_prog_67076c_35d3ef.bin": 132,
    "firstpair_prog_67cc18_369862.bin": 24832,
    "firstpair_reducer67ea28_nibble_373cf4.bin": 64,
    "gf_reduce_table_lib_23840c.bin": 256,
    "libaes_5defec_round1_tables_26f621.bin": 4096,
    "libaes_final_key_tables_276cfc.bin": 4096,
    "libaes_final_table_index_277cfc.bin": 64,
    "libaes_final_table_map_277d3c.bin": 4096,
    "libaes_final_table_words_270624.bin": 16384,
    "libaes_keyexp_consts_276bbc.bin": 320,
    "libaes_keyexp_tables_275bbb.bin": 4096,
    "libaes_round1_tables_278dc2.bin": 4096,
    "libaes_round2_9_tables_279dc4.bin": 16384,
    "params_lib_22a1a0.bin": 56364,
    "phase2_pairs.bin": 2176,
    "phase5_keysched_region_274000.bin": 8192,
    "phone_cert_162b.bin": 162,
    "phone_cert_firstpair.bin": 162,
    "sbox_12bit_full.bin": 2097152,
    "sbox_12bit_lib_9a681a.bin": 65536,
    "sbox_19bit_lib_986819.bin": 524288,
    "singleton_4k.bin": 16384,
    "t5_seed_lib_b25708.bin": 1560,
}

PUBLISHED_COUNT = 57
PUBLISHED_TOTAL = 5261699

CERTS = ("phone_cert_162b.bin", "phone_cert_firstpair.bin")
REGIONS = tuple(n for n in sorted(EXPECTED) if n not in CERTS)

DEFAULT_KIT = "/home/omar/Desktop/LibreCRKit/Sources/LibreCRKit/Resources/RuntimeTables"
PREFERRED_LIB = "liblibre3extension.so"
ANCHOR_LEN = 32


def log(msg):
    print(msg, flush=True)


def sha256(b):
    return hashlib.sha256(b).hexdigest()


def elf_load_segments(data):
    """ELF64 program headers -> [(p_vaddr, p_filesz, p_offset)] for PT_LOAD."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return []
    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
    e_phnum = struct.unpack_from("<H", data, 0x38)[0]
    segs = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if off + 56 > len(data):
            break
        p_type = struct.unpack_from("<I", data, off)[0]
        p_offset, p_vaddr = struct.unpack_from("<QQ", data, off + 8)
        p_filesz = struct.unpack_from("<Q", data, off + 32)[0]
        if p_type == 1:  # PT_LOAD
            segs.append((p_vaddr, p_filesz, p_offset))
    return segs


def vaddr_to_fileoff(data, vaddr):
    for p_vaddr, p_filesz, p_offset in elf_load_segments(data):
        if p_vaddr <= vaddr < p_vaddr + p_filesz:
            return p_offset + (vaddr - p_vaddr)
    return None


def name_offset_tokens(name):
    """6-hex-digit LIB-offset tokens embedded in the kit filename.

    Handles both `*_lib_<hex>` and `*_lib<hex>` (cipher_fn_wbaes_T* glues the
    offset directly onto the 'lib' marker).
    """
    stem = name[:-4] if name.endswith(".bin") else name
    stem = re.sub(r"lib(?=[0-9a-f]{6}(?![0-9a-f]))", "lib_", stem)
    return [int(t, 16) for t in re.findall(r"(?<![0-9a-f])[0-9a-f]{6}(?![0-9a-f])", stem)]


def find_all(hay, needle):
    hits, start = [], 0
    while True:
        i = hay.find(needle, start)
        if i < 0:
            return hits
        hits.append(i)
        start = i + 1


class Extractor:
    def __init__(self, apk_path, outdir, kit_dir):
        self.apk_path = apk_path
        self.outdir = outdir
        self.kit_dir = kit_dir
        self.kit = {}
        self.decisions = []
        self.failed = []
        self.extracted = {}      # name -> bytes
        self.search = {}         # name -> search record from phase A

    def decision(self, name, step, detail):
        self.decisions.append({"name": name, "step": step, "detail": detail})
        log(f"    DECISION [{name}] {step}: {detail}")

    def fail(self, name, reason, evidence=None):
        entry = {"name": name, "reason": reason}
        if evidence:
            entry["evidence"] = evidence
        self.failed.append(entry)
        log(f"    FAIL [{name}] {reason}"
            + (f" — {evidence}" if evidence else ""))

    # ------------------------------------------------------------------
    def load_kit(self):
        if not self.kit_dir or not os.path.isdir(self.kit_dir):
            log(f"  kit bundle unavailable at '{self.kit_dir}': step 3 "
                f"(pattern-anchored fallback) disabled; offset carving only")
            return
        for name in sorted(EXPECTED):
            path = os.path.join(self.kit_dir, name)
            if os.path.isfile(path):
                self.kit[name] = open(path, "rb").read()
        log(f"  kit bundle: {len(self.kit)}/{PUBLISHED_COUNT} files from {self.kit_dir}")

    def load_apk(self):
        log(f"  opening APK: {self.apk_path}")
        self.apk_sha = sha256(open(self.apk_path, "rb").read())
        self.zf = zipfile.ZipFile(self.apk_path)
        self.members = {i.filename: self.zf.read(i.filename)
                        for i in self.zf.infolist()}
        log(f"  APK members: {len(self.members)}")
        self.libs = []
        for member in sorted(self.members):
            parts = member.split("/")
            if len(parts) == 3 and parts[0] == "lib" and parts[2].endswith(".so"):
                self.libs.append((member, parts[2]))

        def order(item):
            member, abi = item
            base = os.path.basename(member)
            return (0 if base == PREFERRED_LIB else 1,
                    0 if abi == "arm64-v8a" else 1, member)
        self.libs.sort(key=order)
        self.preferred = next(
            (m for m, _ in self.libs if os.path.basename(m) == PREFERRED_LIB), None)
        for member, abi in self.libs:
            log(f"  native lib: {member} ({abi}) {len(self.members[member])} B")

    # ------------------------------------------------------------------
    def _step2_attempts(self, name, size, kbytes):
        """README/filename offsets as file offsets and as ELF vaddrs."""
        tokens = name_offset_tokens(name)
        exact, mismatch, oob = [], [], 0
        for tok in tokens:
            for member in [m for m, _ in self.libs]:
                libdata = self.members[member]
                cands = [("file-offset", tok)]
                voff = vaddr_to_fileoff(libdata, tok)
                if voff is not None and voff != tok:
                    cands.append(("vaddr", voff))
                for interp, off in cands:
                    if off + size > len(libdata):
                        oob += 1
                        continue
                    seg = libdata[off:off + size]
                    if seg == kbytes:
                        exact.append((member, off, interp))
                    else:
                        mismatch.append((member, off, interp))
        return exact, mismatch, oob

    def _step3_anchor(self, name, kbytes):
        """32-byte-anchor search across every native lib."""
        anchor = kbytes[:min(ANCHOR_LEN, len(kbytes))]
        exact, divergent = [], []
        for member in [m for m, _ in self.libs]:
            libdata = self.members[member]
            for hit in find_all(libdata, anchor):
                seg = libdata[hit:hit + len(kbytes)]
                if len(seg) == len(kbytes) and seg == kbytes:
                    exact.append((member, hit))
                else:
                    ndiff = sum(a != b for a, b in zip(seg, kbytes)) \
                        + (len(kbytes) - len(seg))
                    divergent.append((member, hit, ndiff))
        return exact, divergent

    def search_regions(self):
        for name in REGIONS:
            size = EXPECTED[name]
            kbytes = self.kit.get(name)
            tokens = name_offset_tokens(name)
            log(f"  region {name} ({size} B); filename offset tokens: "
                f"{[hex(t) for t in tokens] or 'none'}")
            if kbytes is None:
                self.search[name] = {"status": "no-kit"}
                self.fail(name, "kit bundle file missing; cannot anchor or verify")
                continue
            if len(kbytes) != size:
                self.search[name] = {"status": "kit-size-mismatch"}
                self.fail(name, f"kit size {len(kbytes)} != published size {size}")
                continue
            s2exact, s2mismatch, oob = self._step2_attempts(name, size, kbytes)
            s3exact, s3div = self._step3_anchor(name, kbytes)
            if s2mismatch:
                shown = ", ".join(f"{os.path.basename(m)}@{hex(o)}({i})"
                                  for m, o, i in s2mismatch[:6])
                self.decision(name, "step2 filename-offset carve",
                              f"{len(s2mismatch)} in-bounds attempts byte-mismatch"
                              + (f"; {oob} out-of-bounds" if oob else "")
                              + f": {shown}")
            elif oob:
                self.decision(name, "step2 filename-offset carve",
                              f"{oob} candidate offset(s) out of bounds in every "
                              f"native lib (offsets belong to the kit corpus lib, "
                              f"a different build)")
            self.search[name] = {
                "status": "searched",
                "step2_exact": s2exact, "step3_exact": s3exact, "step3_divergent": s3div,
            }
            if s3div and not s3exact:
                member, off, ndiff = min(s3div, key=lambda x: x[2])
                self.decision(name, "step3 anchor divergence",
                              f"anchor found at {os.path.basename(member)}@{hex(off)} "
                              f"but {ndiff}/{len(kbytes)} bytes differ "
                              f"(distilled/non-contiguous artifact)")

    def _cluster_deltas(self):
        deltas = {}
        for name, res in self.search.items():
            if res.get("status") != "searched":
                continue
            offs = res["step2_exact"] or res["step3_exact"]
            if len(offs) != 1:
                continue
            off = offs[0][1]
            toks = name_offset_tokens(name)
            if not toks:
                continue
            tok = min(toks, key=lambda t: abs(off - t))
            deltas.setdefault(off - tok, set()).add(name)
        return deltas

    def finalize_regions(self):
        cluster = self._cluster_deltas()
        log("  -- deciding (byte-exact writes only; fail-closed) --")
        for name in REGIONS:
            res = self.search.get(name, {})
            if res.get("status") != "searched":
                continue
            if res["step2_exact"]:
                member, off, interp = res["step2_exact"][0]
                self._write(name, member, off,
                            f"filename offset {hex(off)} ({interp}) matches kit "
                            f"bytes exactly")
                continue
            exact = res["step3_exact"]
            if not exact:
                if res["step3_divergent"]:
                    member, off, ndiff = min(res["step3_divergent"], key=lambda x: x[2])
                    self.fail(name, "byte-exact content absent from APK",
                              f"32-byte anchor found at {os.path.basename(member)}"
                              f"@{hex(off)} but {ndiff}/{EXPECTED[name]} bytes differ "
                              f"(distilled/non-contiguous runtime artifact); "
                              f"divergent bytes not written")
                else:
                    self.fail(name, "byte-exact content absent from APK",
                              "32-byte anchor not present in any native lib")
                continue
            toks = name_offset_tokens(name)
            if len(exact) == 1:
                member, off = exact[0]
                self._write(name, member, off,
                            "unique byte-exact match via 32-byte anchor "
                            "(offset drift vs kit corpus lib)")
                continue
            scored = []
            for member, off in exact:
                d = min((off - t for t in toks), key=abs) if toks else None
                scored.append((member, off, d))
            matched = [s for s in scored if s[2] in cluster]
            # prefer the occurrence whose delta has the most cluster support,
            # then the smallest |delta|
            pick = min(matched or scored,
                       key=lambda s: (-len(cluster.get(s[2], ()))
                                      if s[2] in cluster else -1,
                                      abs(s[2]) if s[2] is not None else 1 << 62))
            member, off, d = pick
            note = ("drift-cluster delta match" if matched else
                    "nearest-to-filename-offset (ambiguous)")
            self._write(name, member, off,
                        f"{len(exact)} byte-exact occurrences; chose delta "
                        f"{hex(d) if d is not None else '—'} via {note}")

    def _write(self, name, member, off, detail):
        data = self.members[member][off:off + EXPECTED[name]]
        self.extracted[name] = data
        toks = name_offset_tokens(name)
        tok = min(toks, key=lambda t: abs(off - t)) if toks else None
        d = off - tok if tok is not None else None
        self.decision(
            name, "extracted",
            f"{os.path.basename(member)}@{hex(off)}; filename offset "
            f"{hex(tok) if tok is not None else '—'}; drift delta "
            f"{hex(d) if d is not None else '—'}; {detail}")

    # ------------------------------------------------------------------
    def extract_certs(self):
        for name in CERTS:
            size = EXPECTED[name]
            kbytes = self.kit.get(name)
            log(f"  searching {name} ({size} B) byte-exact in every APK member")
            if kbytes is None:
                self.fail(name, "kit bundle file missing; cannot anchor search")
                continue
            hits = []
            for member, data in sorted(self.members.items()):
                for off in find_all(data, kbytes):
                    hits.append((member, off))
            if hits:
                member, off = hits[0]
                self.extracted[name] = kbytes
                extra = f"; {len(hits)} occurrence(s)" if len(hits) > 1 else ""
                self.decision(name, "extracted",
                              f"byte-exact in {member}@{hex(off)}{extra}")
            else:
                self.fail(name,
                          "byte-exact 162-byte content absent from every APK member "
                          "(dex, so, assets, resources.arsc); not synthesized")

    # ------------------------------------------------------------------
    def run(self):
        log("== Libre 3 runtime-table extraction (PLAN_T1DMDROID.md §9) ==")
        total = sum(EXPECTED.values())
        log(f"  published manifest: {PUBLISHED_COUNT} files / {PUBLISHED_TOTAL} B "
            f"(embedded §12.3 list sums to {total} B: "
            f"{'OK' if total == PUBLISHED_TOTAL else 'MISMATCH'})")
        self.load_apk()
        self.load_kit()
        log("  -- searching regions (fallback chain §9 2e) --")
        self.search = {}
        self.search_regions()
        self.finalize_regions()
        self.extract_certs()

        os.makedirs(self.outdir, exist_ok=True)
        for name, data in sorted(self.extracted.items()):
            with open(os.path.join(self.outdir, name), "wb") as fh:
                fh.write(data)
        manifest = {
            "generated_from": {
                "apk": os.path.abspath(self.apk_path),
                "apk_sha256": self.apk_sha,
                "preferred_lib": self.preferred,
                "libs": [{"member": m, "name": os.path.basename(m),
                          "size": len(self.members[m])} for m, _ in self.libs],
                "kit_bundle": self.kit_dir or None,
            },
            "tables_published": {"count": PUBLISHED_COUNT,
                                 "total_bytes": PUBLISHED_TOTAL,
                                 "source": "PLAN_T1DMDROID.md §12.3"},
            "files": [{"name": n, "size": len(d), "sha256": sha256(d)}
                      for n, d in sorted(self.extracted.items())],
            "failed": self.failed,
            "decisions": self.decisions,
        }
        with open(os.path.join(self.outdir, "manifest.json"), "w") as fh:
            json.dump(manifest, fh, indent=2, sort_keys=True)
        log(f"  wrote {len(self.extracted)} table file(s) + manifest.json -> "
            f"{self.outdir}")
        return manifest


def verify_only(outdir):
    log("== verify-only ==")
    mpath = os.path.join(outdir, "manifest.json")
    if not os.path.isfile(mpath):
        log(f"  ERROR: {mpath} not found")
        return 1
    manifest = json.load(open(mpath))
    ok = True
    got = 0
    log(f"  {'name':52s} {'size':>9}  sha256       status")
    for entry in manifest.get("files", []):
        name = entry["name"]
        path = os.path.join(outdir, name)
        if not os.path.isfile(path):
            log(f"  {name:52s} {entry['size']:9d}  {entry['sha256'][:12]}…  MISSING")
            ok = False
            continue
        data = open(path, "rb").read()
        h = sha256(data)
        if len(data) != entry["size"] or h != entry["sha256"]:
            log(f"  {name:52s} {len(data):9d}  {h[:12]}…  MISMATCH")
            ok = False
        else:
            got += 1
            log(f"  {name:52s} {len(data):9d}  {h[:12]}…  OK")
    total = sum(e["size"] for e in manifest.get("files", []))
    log(f"  manifest files verified: {got}/{len(manifest.get('files', []))}; "
        f"§12.3 coverage {got}/{PUBLISHED_COUNT} files, {total}/{PUBLISHED_TOTAL} B")
    missing = sorted(set(EXPECTED) - {e["name"] for e in manifest.get("files", [])})
    if missing:
        log("  §12.3 shortfall: " + ", ".join(missing))
        ok = False
    log(f"  VERIFY-ONLY RESULT: {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


def main(argv):
    ap = argparse.ArgumentParser(description="Libre 3 runtime-table extractor (§9)")
    ap.add_argument("apk", nargs="?", help="path to the vendor APK")
    ap.add_argument("-o", "--outdir", default="tables", help="output dir (gitignored)")
    ap.add_argument("--kit", default=DEFAULT_KIT,
                    help="LibreCRKit RuntimeTables bundle (byte ground truth)")
    ap.add_argument("--verify-only", action="store_true",
                    help="re-check OUTDIR against manifest.json + §12.3 sizes")
    args = ap.parse_args(argv)

    if args.verify_only:
        return verify_only(args.outdir)
    if not args.apk:
        ap.error("apk path required (or --verify-only)")
    if sum(EXPECTED.values()) != PUBLISHED_TOTAL:
        log("  WARNING: embedded §12.3 size table does not sum to published total")

    ex = Extractor(args.apk, args.outdir, args.kit)
    ex.run()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))