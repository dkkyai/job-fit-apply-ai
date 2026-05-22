"""Generate simple placeholder icons for the extension."""
import struct, zlib, os

def make_png(size, color=(57, 224, 122)):
    """Create a solid-color square PNG."""
    def chunk(name, data):
        c = zlib.crc32(name + data) & 0xffffffff
        return struct.pack('>I', len(data)) + name + data + struct.pack('>I', c)

    ihdr = struct.pack('>IIBBBBB', size, size, 8, 2, 0, 0, 0)
    raw  = b''
    for _ in range(size):
        raw += b'\x00' + bytes(color) * size
    idat = zlib.compress(raw)

    return (
        b'\x89PNG\r\n\x1a\n' +
        chunk(b'IHDR', ihdr) +
        chunk(b'IDAT', idat) +
        chunk(b'IEND', b'')
    )

os.makedirs('icons', exist_ok=True)
for sz in [16, 32, 48, 128]:
    with open(f'icons/icon{sz}.png', 'wb') as f:
        f.write(make_png(sz))
    print(f'  icon{sz}.png ✓')
