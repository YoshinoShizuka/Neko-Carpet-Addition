"""极简 NBT 读取器：把 .dat（gzip 或裸 NBT）解析成 python 对象后按需打印。

用途：取证实机存档里玩家的经验字段与物品 NBT（XpLevel / XpP / XpTotal / 鞘翅 Damage）。
只读，不写任何文件。
"""
import gzip
import struct
import sys


class Reader:
    def __init__(self, data: bytes):
        self.d = data
        self.i = 0

    def u1(self):
        v = self.d[self.i]
        self.i += 1
        return v

    def u2(self):
        v = struct.unpack_from('>H', self.d, self.i)[0]
        self.i += 2
        return v

    def i4(self):
        v = struct.unpack_from('>i', self.d, self.i)[0]
        self.i += 4
        return v

    def i8(self):
        v = struct.unpack_from('>q', self.d, self.i)[0]
        self.i += 8
        return v

    def f4(self):
        v = struct.unpack_from('>f', self.d, self.i)[0]
        self.i += 4
        return v

    def f8(self):
        v = struct.unpack_from('>d', self.d, self.i)[0]
        self.i += 8
        return v

    def s(self):
        n = self.u2()
        v = self.d[self.i:self.i + n].decode('utf-8', 'replace')
        self.i += n
        return v

    def payload(self, t):
        if t == 1:
            return struct.unpack_from('>b', self.d, self.i - 1)[0] if False else self.i1()
        if t == 2:
            return self.i2()
        if t == 3:
            return self.i4()
        if t == 4:
            return self.i8()
        if t == 5:
            return self.f4()
        if t == 6:
            return self.f8()
        if t == 7:
            n = self.i4()
            v = list(self.d[self.i:self.i + n])
            self.i += n
            return bytes(v)
        if t == 8:
            return self.s()
        if t == 9:
            et = self.u1()
            n = self.i4()
            return [self.payload(et) for _ in range(n)]
        if t == 10:
            return self.compound()
        if t == 11:
            n = self.i4()
            v = [self.i4() for _ in range(n)]
            return v
        if t == 12:
            n = self.i4()
            return [self.i8() for _ in range(n)]
        raise ValueError(f'unknown tag {t} at {self.i}')

    def i1(self):
        v = struct.unpack_from('>b', self.d, self.i)[0]
        self.i += 1
        return v

    def i2(self):
        v = struct.unpack_from('>h', self.d, self.i)[0]
        self.i += 2
        return v

    def compound(self):
        out = {}
        while True:
            t = self.u1()
            if t == 0:
                return out
            name = self.s()
            out[name] = self.payload(t)


def load(path):
    raw = open(path, 'rb').read()
    if raw[:2] == b'\x1f\x8b':
        raw = gzip.decompress(raw)
    r = Reader(raw)
    t = r.u1()
    assert t == 10, f'root not compound: {t}'
    r.s()  # root name
    return r.compound()


if __name__ == '__main__':
    root = load(sys.argv[1])
    keys = sys.argv[2:] if len(sys.argv) > 2 else ['XpLevel', 'XpP', 'XpTotal', 'Health',
                                                   'EnderItems', 'Inventory']
    for k in keys:
        v = root.get(k)
        if isinstance(v, bytes):
            v = f'<bytes {len(v)}>'
        print(f'{k} = {v}')
