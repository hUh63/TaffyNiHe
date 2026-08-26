#!/usr/bin/env python3
"""
ApkSigningBlock v2/v3 证书提取逻辑回归验证（与 nativecore/SignatureVerifier.kt
的 ApkSigningBlock 对象逐行等价）。用法:
  1. python3 scripts/verify_signing_block.py 生成 v2 测试 APK
  2. 或用任意真实 v2/v3 签名 APK 验证
验证点:
  - EOCD 定位 + "APK Sig Block 42" magic
  - block_start = cd_offset - size - 8（重要: 不是 block_end - size - 8）
  - leading == trailing size 交叉校验
  - signers → signer → signedData → digests → certificates → [u32 len][DER] 提取
"""
import struct, hashlib, sys

def extract_cert_from_sign_block(block):
    """与 Kotlin extractCertFromSignBlock 逐行等价。"""
    pos = 0
    def u32():
        nonlocal pos
        if pos + 4 > len(block): return -1
        v = struct.unpack_from('<I', block, pos)[0]
        pos += 4
        return v
    signers_len = u32()
    if signers_len < 0 or pos + signers_len > len(block): return None
    signer_len = u32()
    if signer_len < 0 or pos + signer_len > len(block): return None
    signer_end = pos + signer_len
    sd_len = u32()
    if sd_len < 0 or pos + sd_len > signer_end: return None
    sd_end = pos + sd_len
    digests_len = u32()
    if digests_len < 0 or pos + digests_len > sd_end: return None
    pos += digests_len
    certs_len = u32()
    if certs_len < 0 or pos + certs_len > sd_end: return None
    certs_end = pos + certs_len
    cert_len = u32()
    if cert_len < 0 or pos + cert_len > certs_end: return None
    return block[pos:pos + cert_len]

def signing_block_cert_digest(apk_path):
    """与 Kotlin signingBlockCertDigest 等价。返回 (digest_or_None, parse_error_bool)。"""
    data = open(apk_path, 'rb').read()
    L = len(data)
    eocd_pos = -1
    pos = L - 22
    while pos >= max(0, L - 65557):
        if data[pos:pos+4] == b'PK\x05\x06': eocd_pos = pos; break
        pos -= 1
    if eocd_pos < 0: return None, False
    cd_off = struct.unpack_from('<I', data, eocd_pos + 16)[0]
    if cd_off < 24 or cd_off > L: return None, False
    magic_off = cd_off - 16
    if data[magic_off:magic_off+16] != b'APK Sig Block 42': return None, False
    trailing = struct.unpack_from('<Q', data, magic_off - 8)[0]
    if trailing <= 0 or trailing > magic_off - 8 - 8: return None, True
    block_start = cd_off - trailing - 8          # 关键公式
    leading = struct.unpack_from('<Q', data, block_start)[0]
    if leading != trailing: return None, True
    pair_pos = block_start + 8
    block_end = magic_off - 8
    v2_cert = v3_cert = None
    while pair_pos + 8 <= block_end:
        pair_size = struct.unpack_from('<Q', data, pair_pos)[0]
        value_off = pair_pos + 8
        if pair_size < 4: pair_pos = value_off + pair_size; continue
        if value_off + pair_size > block_end: return None, True
        bid = struct.unpack_from('<I', data, value_off)[0]
        value = data[value_off+4:value_off+pair_size]
        if bid == 0xf05368c0: v3_cert = extract_cert_from_sign_block(value)
        elif bid == 0x7109871a: v2_cert = extract_cert_from_sign_block(value)
        pair_pos = value_off + pair_size
    cert = v3_cert or v2_cert
    if not cert: return None, False
    return hashlib.sha256(cert).hexdigest().upper(), False

if __name__ == '__main__':
    apk = sys.argv[1] if len(sys.argv) > 1 else '/tmp/mini-signed.apk'
    d, err = signing_block_cert_digest(apk)
    if err:
        print(f'{apk}: 签名块存在但解析失败 (PARSE_ERROR)')
        sys.exit(2)
    if d is None:
        print(f'{apk}: 无 v2/v3 签名块')
        sys.exit(0)
    print(f'{apk}: v2/v3 证书 SHA-256 = {d}')
