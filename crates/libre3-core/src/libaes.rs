//! Clean-room port of Abbott's Phase 5 block primitive (lib+0x5dec48..0x5e45e4): a table-driven
//! AES-like primitive, NOT CommonCrypto AES. Tables come from the runtime-table set delivered
//! out of band (PLAN_T1DMDROID.md §9); this module never embeds table bytes.

use crate::CryptoError;

/// Expanded key context size.
pub const CONTEXT_SIZE: usize = 0x10b0;

/// The libaes table set, validated on load. Names follow the runtime-table naming.
pub struct LibAESTables {
    round1_tables: Vec<u8>,
    round2_tables: Vec<u32>,
    phase5_round1_tables: Vec<u8>,
    phase5_round_tables: Vec<u32>,
    keyexp_tables: Vec<u8>,
    keyexp_consts: Vec<u8>,
    final_key_tables: Vec<u8>,
    final_table_index: Vec<u8>,
    final_table_map: Vec<u8>,
    final_table_words: Vec<u8>,
}

impl LibAESTables {
    /// Loads the nine .bin files from a directory and validates their sizes.
    pub fn from_dir(dir: &std::path::Path) -> Result<LibAESTables, CryptoError> {
        let read = |name: &str, want: usize| -> Result<Vec<u8>, CryptoError> {
            let bytes = std::fs::read(dir.join(format!("{name}.bin")))
                .map_err(|_| CryptoError::TablesMissing { name: name.to_owned() })?;
            if bytes.len() != want {
                return Err(CryptoError::TablesSize {
                    name: name.to_owned(),
                    want,
                    got: bytes.len(),
                });
            }
            Ok(bytes)
        };
        let round1_tables = read("libaes_round1_tables_278dc2", 0x1000)?;
        let round2 = read("libaes_round2_9_tables_279dc4", 0x4000)?;
        let phase5_round1_tables = read("libaes_5defec_round1_tables_26f621", 0x1000)?;
        let keyexp_tables = read("libaes_keyexp_tables_275bbb", 0x1000)?;
        let keyexp_consts = read("libaes_keyexp_consts_276bbc", 320)?;
        let final_key_tables = read("libaes_final_key_tables_276cfc", 0x1000)?;
        let final_table_index = read("libaes_final_table_index_277cfc", 0x40)?;
        let final_table_map = read("libaes_final_table_map_277d3c", 0x1000)?;
        let final_table_words = read("libaes_final_table_words_270624", 0x4000)?;

        Ok(LibAESTables {
            phase5_round_tables: words_le(&final_table_words),
            round2_tables: words_le(&round2),
            round1_tables,
            phase5_round1_tables,
            keyexp_tables,
            keyexp_consts,
            final_key_tables,
            final_table_index,
            final_table_map,
            final_table_words,
        })
    }
}

fn words_le(bytes: &[u8]) -> Vec<u32> {
    bytes
        .chunks_exact(4)
        .map(|c| u32::from_le_bytes([c[0], c[1], c[2], c[3]]))
        .collect()
}

/// Expanded-key context (0x10b0 bytes): raw key at 0, round keys from 0x10, dynamic tables
/// built at 0xb0.
pub fn key_setup(raw_key: &[u8], t: &LibAESTables) -> Result<Vec<u8>, CryptoError> {
    if raw_key.len() != 16 {
        return Err(CryptoError::LibAes {
            reason: format!("key must be 16 bytes, got {}", raw_key.len()),
        });
    }
    let mut ctx = vec![0u8; CONTEXT_SIZE];
    ctx[..16].copy_from_slice(raw_key);

    let mut out_off = 0x10usize;
    let mut const_a = 0x08usize;
    let mut const_b = 0xa8usize;
    let mut loop_ctr: isize = -4;
    let mut w13 = read_u32(&ctx, 0x0c);

    loop {
        let w14 = read_u32(&t.keyexp_consts, const_a - 8);
        loop_ctr += 4;
        let keep_going = loop_ctr < 0x24;

        w13 = w14 ^ w13.rotate_right(24);
        let mut sub = subword(&t.keyexp_tables, w13, 0);
        let prev0 = read_u32(&ctx, out_off - 0x10);
        let prev1 = read_u32(&ctx, out_off - 0x0c);
        let w15 = prev0 ^ read_u32(&t.keyexp_consts, const_b - 8);
        w13 = w15 ^ sub;
        put_u32(&mut ctx, out_off, w13);

        w13 = read_u32(&t.keyexp_consts, const_a - 4) ^ w13;
        sub = subword(&t.keyexp_tables, w13, 1);
        let w14_mix = prev1 ^ read_u32(&t.keyexp_consts, const_b - 4);
        w13 = w14_mix ^ sub;
        put_u32(&mut ctx, out_off + 0x04, w13);

        w13 = read_u32(&t.keyexp_consts, const_a) ^ w13;
        sub = subword(&t.keyexp_tables, w13, 2);
        let prev2 = read_u32(&ctx, out_off - 0x08);
        let prev3 = read_u32(&ctx, out_off - 0x04);
        let w15 = prev2 ^ read_u32(&t.keyexp_consts, const_b);
        w13 = w15 ^ sub;
        put_u32(&mut ctx, out_off + 0x08, w13);

        w13 = read_u32(&t.keyexp_consts, const_a + 0x04) ^ w13;
        const_a += 0x10;
        sub = subword(&t.keyexp_tables, w13, 3);
        let w14_mix = prev3 ^ read_u32(&t.keyexp_consts, const_b + 0x04);
        const_b += 0x10;
        w13 = w14_mix ^ sub;
        put_u32(&mut ctx, out_off + 0x0c, w13);

        out_off += 0x10;
        if !keep_going {
            break;
        }
    }

    for group in 0..4usize {
        let off = 0xa0 + group * 4;
        let v = subword(&t.final_key_tables, read_u32(&ctx, off), group);
        put_u32(&mut ctx, off, v);
    }

    for table_idx in 0..16usize {
        let word_table_idx = read_u32(&t.final_table_index, table_idx * 4) as usize;
        let word_table_base = word_table_idx * 0x400;
        let map_base = table_idx * 0x100;
        let key_word = read_u32(&ctx, 0xa0 + (table_idx >> 2) * 4);
        let shift = (24 - 8 * (table_idx & 3)) as u32;
        let dst = 0xb0 + table_idx * 0x100;
        for i in 0..256usize {
            let mixed = key_word ^ read_u32(&t.final_table_words, word_table_base + i * 4);
            ctx[dst + i] = t.final_table_map[map_base + ((mixed >> shift) & 0xff) as usize];
        }
    }

    Ok(ctx)
}

/// The Phase 5 block encryptor (lib+0x5e41b4..0x5e45e4), translated statement-for-statement
/// from LibAES.swift blockEncrypt; variable names and mutation order preserved.
#[allow(unused_assignments)]
pub fn block_encrypt(
    plaintext: &[u8],
    ctx: &[u8],
    t: &LibAESTables,
) -> Result<[u8; 16], CryptoError> {
    if plaintext.len() != 16 {
        return Err(CryptoError::LibAes { reason: format!("block must be 16 bytes, got {}", plaintext.len()) });
    }
    if ctx.len() < CONTEXT_SIZE {
        return Err(CryptoError::LibAes { reason: format!("context must be at least {CONTEXT_SIZE} bytes") });
    }

    let mut state = [0u8; 16];
    for c in 0..4usize {
        for r in 0..4usize {
            let ti = c * 4 + (3 - r);
            state[c * 4 + r] =
                t.round1_tables[ti * 0x100 + plaintext[ti] as usize] ^ ctx[c * 4 + r];
        }
    }


    let mut w16 = read_u32(&state, 0);
    let mut w17 = read_u32(&state, 4);
    let mut w14 = read_u32(&state, 8);
    let mut w13 = read_u32(&state, 12);
    let mut x8: usize = 0;
    let mut w11: u32 = 0;
    let mut w15: u32 = 0;
    loop {
            w11 = w17 & 0xff;
            w15 = ubfx(w13, 16, 8);
            let mut w0 = ubfx(w14, 8, 8);
            let mut x11i = w11;
            let mut w3 = w16 >> 24;
            let mut w6 = read_u32(ctx, 0x10 + x8);
            let mut w7 = read_u32(ctx, 0x14 + x8);
            let mut x15i = w15;
            let mut x0i = w0;
            w11 = table_word(&t.round2_tables, 7, x11i);
            let mut w5 = w14 & 0xff;
            let mut w4 = ubfx(w13, 8, 8);
            w3 = table_word(&t.round2_tables, 0, w3);
            let mut x5i = w5;
            w15 = table_word(&t.round2_tables, 13, x15i);
            w0 = table_word(&t.round2_tables, 10, x0i);
            w11 ^= w6;
            w6 = ubfx(w16, 16, 8);
            let mut w19 = w17 >> 24;
            w11 ^= w15;
            w15 = w0 ^ w3;
            x0i = w4;
            w3 = table_word(&t.round2_tables, 11, x5i);
            let mut x4i = w6;
            x5i = w19;
            w6 = w13 & 0xff;
            w0 = table_word(&t.round2_tables, 14, x0i);
            w19 = w14 >> 24;
            w4 = table_word(&t.round2_tables, 1, x4i);
            w5 = table_word(&t.round2_tables, 4, x5i);
            let mut x6i = w6;
            w3 ^= w7;
            let x7i = w19;
            w11 ^= w15;
            w15 = w3 ^ w0;
            w0 = w4 ^ w5;
            w6 = table_word(&t.round2_tables, 15, x6i);
            w19 = read_u32(ctx, 0x18 + x8);
            w4 = read_u32(ctx, 0x1c + x8);
            w3 = table_word(&t.round2_tables, 8, x7i);
            let w7i = ubfx(w17, 16, 8);
            let w14i = ubfx(w14, 16, 8);
            let w17i = ubfx(w17, 8, 8);
            let w13i = w13 >> 24;
            w5 = w19 ^ w6;
            let w6i = ubfx(w16, 8, 8);
            let w16i = w16 & 0xff;
            let w16t = table_word(&t.round2_tables, 3, w16i);
            let w7t = table_word(&t.round2_tables, 5, w7i);
            let w14t = table_word(&t.round2_tables, 9, w14i);
            let w6t = table_word(&t.round2_tables, 2, w6i);
            let w17t = table_word(&t.round2_tables, 6, w17i);
            let w13t = table_word(&t.round2_tables, 12, w13i);
            w16 = w4 ^ w16t;
            w3 = w5 ^ w3;
            w5 = w6t ^ w7t;
            w14 = w17t ^ w14t;
            w16 ^= w13t;
            w13 = w15 ^ w0;
            w15 = w3 ^ w5;
            w14 ^= w16;
        if x8 == 0x80 { break; }
            w17 = w13 & 0xff;
            w16 = w11 >> 24;
            w0 = ubfx(w15, 8, 8);
            w4 = ubfx(w11, 16, 8);
            let x17i = w17;
            w5 = w15 & 0xff;
            w3 = ubfx(w14, 16, 8);
            w6 = ubfx(w14, 8, 8);
            w16 = table_word(&t.round2_tables, 0, w16);
            x0i = w0;
            x4i = w4;
            w17 = table_word(&t.round2_tables, 7, x17i);
            w7 = w13 >> 24;
            x5i = w5;
            let x3iFirst = w3;
            w0 = table_word(&t.round2_tables, 10, x0i);
            w4 = table_word(&t.round2_tables, 1, x4i);
            w16 = w17 ^ w16;
            w17 = table_word(&t.round2_tables, 11, x5i);
            x5i = w6;
            x6i = w7;
            w16 ^= w0;
            w0 = table_word(&t.round2_tables, 13, x3iFirst);
            let oldX8 = x8;
            x8 += 0x20;
            w17 ^= w4;
            w3 = table_word(&t.round2_tables, 14, x5i);
            w4 = table_word(&t.round2_tables, 4, x6i);
            w5 = w14 & 0xff;
            let w14tmp = w14 >> 24;
            w6 = ubfx(w11, 8, 8);
            w3 ^= w4;
            x5i = w5;
            let w11tmp = w11 & 0xff;
            let w7rk = read_u32(ctx, 0x20 + oldX8);
            let w4rk = read_u32(ctx, 0x24 + oldX8);
            w17 ^= w3;
            let x14i = w14tmp;
            x11i = w11tmp;
            let x3i = w6;
            w0 ^= w7rk;
            w17 ^= w4rk;
            let w4idx = ubfx(w15, 16, 8);
            w16 ^= w0;
            w0 = table_word(&t.round2_tables, 15, x5i);
            let w5idx = ubfx(w13, 8, 8);
            let w15tmp = w15 >> 24;
            let w13tmp = ubfx(w13, 16, 8);
            x4i = w4idx;
            x5i = w5idx;
            let w14v = table_word(&t.round2_tables, 12, x14i);
            let w11v = table_word(&t.round2_tables, 3, x11i);
            x15i = w15tmp;
            let x13i = w13tmp;
            let w4v = table_word(&t.round2_tables, 9, x4i);
            let w6rk = read_u32(ctx, 0x28 + oldX8);
            let w12rk = read_u32(ctx, 0x2c + oldX8);
            let w5v = table_word(&t.round2_tables, 6, x5i);
            let w3v = table_word(&t.round2_tables, 2, x3i);
            let w15v = table_word(&t.round2_tables, 8, x15i);
            let w13v = table_word(&t.round2_tables, 5, x13i);
            w11 = w14v ^ w11v;
            w0 ^= w6rk;
            w4 = w5v ^ w4v;
            w14 = w0 ^ w3v;
            w13 = w15v ^ w13v;
            w11 = w4 ^ w11;
            w14 ^= w13;
            w13 = w11 ^ w12rk;
        }
    let mut out = [0u8; 16];
    out[0] = dyn_byte(ctx, 0x0b0, w11 >> 24);
    out[1] = dyn_byte(ctx, 0x1b0, w14 >> 16);
    out[2] = dyn_byte(ctx, 0x2b0, w15 >> 8);
    out[3] = dyn_byte(ctx, 0x3b0, w13);
    out[4] = dyn_byte(ctx, 0x4b0, w13 >> 24);
    out[5] = dyn_byte(ctx, 0x5b0, w11 >> 16);
    out[6] = dyn_byte(ctx, 0x6b0, w14 >> 8);
    out[7] = dyn_byte(ctx, 0x7b0, w15);
    out[8] = dyn_byte(ctx, 0x8b0, w15 >> 24);
    out[9] = dyn_byte(ctx, 0x9b0, w13 >> 16);
    out[10] = dyn_byte(ctx, 0xab0, w11 >> 8);
    out[11] = dyn_byte(ctx, 0xbb0, w14);
    out[12] = dyn_byte(ctx, 0xcb0, w14 >> 24);
    out[13] = dyn_byte(ctx, 0xdb0, w15 >> 16);
    out[14] = dyn_byte(ctx, 0xeb0, w13 >> 8);
    out[15] = dyn_byte(ctx, 0xfb0, w11);
    Ok(out)
}

/// The Phase 5 wire block (lib+0x5defec..0x5df414 family).
pub fn phase5_block_encrypt(
    plaintext: &[u8],
    ctx: &[u8],
    t: &LibAESTables,
) -> Result<[u8; 16], CryptoError> {
    if plaintext.len() != 16 {
        return Err(CryptoError::LibAes {
            reason: format!("block must be 16 bytes, got {}", plaintext.len()),
        });
    }
    if ctx.len() < CONTEXT_SIZE {
        return Err(CryptoError::LibAes {
            reason: format!("context must be at least {CONTEXT_SIZE} bytes"),
        });
    }
    let r1 = &t.phase5_round1_tables;

    let mut w16 = read_u32(ctx, 0x00)
        ^ ((r1[0x000 + plaintext[0] as usize] as u32) << 24)
        ^ ((r1[0x100 + plaintext[1] as usize] as u32) << 16)
        ^ ((r1[0x200 + plaintext[2] as usize] as u32) << 8)
        ^ r1[0x300 + plaintext[3] as usize] as u32;
    let mut w14 = read_u32(ctx, 0x04)
        ^ ((r1[0x400 + plaintext[4] as usize] as u32) << 24)
        ^ ((r1[0x500 + plaintext[5] as usize] as u32) << 16)
        ^ ((r1[0x600 + plaintext[6] as usize] as u32) << 8)
        ^ r1[0x700 + plaintext[7] as usize] as u32;
    let mut w13 = read_u32(ctx, 0x08)
        ^ ((r1[0x800 + plaintext[8] as usize] as u32) << 24)
        ^ ((r1[0x900 + plaintext[9] as usize] as u32) << 16)
        ^ ((r1[0xa00 + plaintext[10] as usize] as u32) << 8)
        ^ r1[0xb00 + plaintext[11] as usize] as u32;
    let mut w15 = read_u32(ctx, 0x0c)
        ^ ((r1[0xc00 + plaintext[12] as usize] as u32) << 24)
        ^ ((r1[0xd00 + plaintext[13] as usize] as u32) << 16)
        ^ ((r1[0xe00 + plaintext[14] as usize] as u32) << 8)
        ^ r1[0xf00 + plaintext[15] as usize] as u32;

    let mut x8 = 0usize;
    let mut w11: u32;
    loop {
        (w11, w14, w13, w15) =
            phase5_first_half(w16, w14, w13, w15, ctx, x8, &t.phase5_round_tables);
        if x8 == 0x80 {
            break;
        }
        (w16, w14, w13, w15) =
            phase5_second_half(w11, w14, w13, w15, ctx, x8, &t.phase5_round_tables);
        x8 += 0x20;
    }

    let mut out = [0u8; 16];
    out[0] = dyn_byte(ctx, 0x0b0, w11 >> 24);
    out[1] = dyn_byte(ctx, 0x1b0, w14 >> 16);
    out[2] = dyn_byte(ctx, 0x2b0, w13 >> 8);
    out[3] = dyn_byte(ctx, 0x3b0, w15);
    out[4] = dyn_byte(ctx, 0x4b0, w14 >> 24);
    out[5] = dyn_byte(ctx, 0x5b0, w13 >> 16);
    out[6] = dyn_byte(ctx, 0x6b0, w15 >> 8);
    out[7] = dyn_byte(ctx, 0x7b0, w11);
    out[8] = dyn_byte(ctx, 0x8b0, w13 >> 24);
    out[9] = dyn_byte(ctx, 0x9b0, w15 >> 16);
    out[10] = dyn_byte(ctx, 0xab0, w11 >> 8);
    out[11] = dyn_byte(ctx, 0xbb0, w14);
    out[12] = dyn_byte(ctx, 0xcb0, w15 >> 24);
    out[13] = dyn_byte(ctx, 0xdb0, w11 >> 16);
    out[14] = dyn_byte(ctx, 0xeb0, w14 >> 8);
    out[15] = dyn_byte(ctx, 0xfb0, w13);
    Ok(out)
}

#[inline]
fn ubfx(x: u32, lsb: u32, width: u32) -> u32 {
    (x >> lsb) & ((1u32 << width) - 1)
}

#[inline]
fn read_u32(b: &[u8], off: usize) -> u32 {
    u32::from_le_bytes([b[off], b[off + 1], b[off + 2], b[off + 3]])
}

#[inline]
fn put_u32(b: &mut [u8], off: usize, v: u32) {
    b[off..off + 4].copy_from_slice(&v.to_le_bytes());
}

#[inline]
fn subword(table: &[u8], value: u32, group: usize) -> u32 {
    let base = group * 0x400;
    let b0 = (value & 0xff) as usize;
    let b1 = ((value >> 8) & 0xff) as usize;
    let b2 = ((value >> 16) & 0xff) as usize;
    let b3 = ((value >> 24) & 0xff) as usize;
    (table[base + 0x300 + b0] as u32)
        | ((table[base + 0x100 + b2] as u32) << 16)
        | ((table[base + 0x200 + b1] as u32) << 8)
        | ((table[base + b3] as u32) << 24)
}

#[inline]
fn table_word(table: &[u32], table_idx: usize, index: u32) -> u32 {
    table[table_idx * 256 + (index & 0xff) as usize]
}

#[inline]
fn dyn_byte(ctx: &[u8], off: usize, idx: u32) -> u8 {
    ctx[off + (idx & 0xff) as usize]
}

#[inline]
fn phase5_first_half(
    w16: u32,
    w14: u32,
    w13: u32,
    w15: u32,
    ctx: &[u8],
    x8: usize,
    t: &[u32],
) -> (u32, u32, u32, u32) {
    let rk0 = read_u32(ctx, 0x10 + x8);
    let rk1 = read_u32(ctx, 0x14 + x8);
    let rk2 = read_u32(ctx, 0x18 + x8);
    let rk3 = read_u32(ctx, 0x1c + x8);

    let out11 = table_word(t, 0, w16 >> 24)
        ^ rk0
        ^ table_word(t, 15, w15)
        ^ table_word(t, 5, ubfx(w14, 16, 8))
        ^ table_word(t, 10, ubfx(w13, 8, 8));

    let out14 = (rk1 ^ table_word(t, 3, w16) ^ table_word(t, 9, ubfx(w13, 16, 8)))
        ^ (table_word(t, 4, w14 >> 24) ^ table_word(t, 14, ubfx(w15, 8, 8)));

    let out13 = table_word(t, 13, ubfx(w15, 16, 8))
        ^ table_word(t, 7, w14)
        ^ table_word(t, 2, ubfx(w16, 8, 8))
        ^ table_word(t, 8, w13 >> 24)
        ^ rk2;

    let out15 = (table_word(t, 6, ubfx(w14, 8, 8)) ^ rk3)
        ^ (table_word(t, 12, w15 >> 24) ^ table_word(t, 11, w13))
        ^ table_word(t, 1, ubfx(w16, 16, 8));

    (out11, out14, out13, out15)
}

#[inline]
fn phase5_second_half(
    w11: u32,
    w14: u32,
    w13: u32,
    w15: u32,
    ctx: &[u8],
    x8: usize,
    t: &[u32],
) -> (u32, u32, u32, u32) {
    let rk0 = read_u32(ctx, 0x20 + x8);
    let rk1 = read_u32(ctx, 0x24 + x8);
    let rk2 = read_u32(ctx, 0x28 + x8);
    let rk3 = read_u32(ctx, 0x2c + x8);

    let out16 = table_word(t, 15, w15)
        ^ table_word(t, 0, w11 >> 24)
        ^ table_word(t, 10, ubfx(w13, 8, 8))
        ^ table_word(t, 5, ubfx(w14, 16, 8))
        ^ rk0;

    let out14 = (table_word(t, 4, w14 >> 24)
        ^ table_word(t, 3, w11)
        ^ table_word(t, 14, ubfx(w15, 8, 8)))
        ^ rk1
        ^ table_word(t, 9, ubfx(w13, 16, 8));

    let out13 = (table_word(t, 13, ubfx(w15, 16, 8))
        ^ rk2
        ^ table_word(t, 2, ubfx(w11, 8, 8)))
        ^ table_word(t, 8, w13 >> 24)
        ^ table_word(t, 7, w14);

    let out15 = (table_word(t, 11, w13) ^ table_word(t, 1, ubfx(w11, 16, 8)) ^ rk3)
        ^ (table_word(t, 12, w15 >> 24) ^ table_word(t, 6, ubfx(w14, 8, 8)));

    (out16, out14, out13, out15)
}
