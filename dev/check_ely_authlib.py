#!/usr/bin/env python3
"""Check the exact ElyPrism 7.0.61-ely.1 library's integration shape, without executing it."""
import hashlib
import io
import struct
import urllib.request
import zipfile

URL = 'https://repo.llaun.ch/libraries/by/ely/authlib/7.0.61-ely.1/authlib-7.0.61-ely.1.jar'
# From ElyPrismLauncher/meta-launcher/by.ely.authlib/7.0.61.json.
SHA1 = 'af1191a79834f44dcc582291b7325954878047f7'


def field_descriptors(data):
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('Not a Java class')
    count = struct.unpack_from('>H', data, 8)[0]
    pos, index, strings = 10, 1, {}
    while index < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            length = struct.unpack_from('>H', data, pos)[0]
            pos += 2
            strings[index] = data[pos:pos + length].decode('utf-8', errors='replace')
            pos += length
        elif tag in (7, 8, 16, 19, 20):
            pos += 2
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            index += 1
        elif tag == 15:
            pos += 3
        else:
            raise ValueError('Unknown constant pool tag')
        index += 1
    pos += 6  # access, this, super
    interfaces = struct.unpack_from('>H', data, pos)[0]
    pos += 2 + 2 * interfaces
    fields = struct.unpack_from('>H', data, pos)[0]
    pos += 2
    result = []
    for _ in range(fields):
        _, _, descriptor, attributes = struct.unpack_from('>HHHH', data, pos)
        pos += 8
        result.append(strings[descriptor])
        for _ in range(attributes):
            length = struct.unpack_from('>I', data, pos + 2)[0]
            pos += 6 + length
    return result


def main():
    with urllib.request.urlopen(URL, timeout=45) as response:
        data = response.read(2 * 1024 * 1024 + 1)
    if len(data) > 2 * 1024 * 1024 or hashlib.sha1(data).hexdigest() != SHA1:
        raise ValueError('Unexpected ElyPrism library contents: size=' + str(len(data)) + ', sha1=' + hashlib.sha1(data).hexdigest())
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        assert 'by/ely/authlib/ElyProfileService.class' in archive.namelist(), 'ElyProfileService absent; classes: ' + ','.join(n for n in archive.namelist() if n.startswith('by/ely/'))
        fields = field_descriptors(archive.read('com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService.class'))
        assert 'Lby/ely/authlib/ElyProfileService;' in fields, 'Unsupported replacement session implementation: ' + repr(fields)
    print('::notice::ElyPrism authlib 7.0.61-ely.1: checksum and integrated session field verified (not a game login test).')


if __name__ == '__main__':
    try:
        main()
    except Exception as ex:
        message = (type(ex).__name__ + ': ' + str(ex)).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print('::error title=ElyPrism compatibility probe::' + message)
        raise
