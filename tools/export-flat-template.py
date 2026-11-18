"""从关闭的 1.21.1 超平坦存档或 ZIP 备份只读导出候选模板（需要 nbtlib）。"""

import argparse
import gzip
import io
from contextlib import ExitStack
from functools import lru_cache
from pathlib import Path
import struct
from zipfile import ZipFile
import zlib

import nbtlib as nbt


def export(source, output, first, last):
    """只使用用户指定的闭区间；原点和空气含义留给正式接线做显式裁决。"""
    if any(a > b for a, b in zip(first, last)):
        raise ValueError("最小角点不能大于最大角点")
    size = tuple(b - a + 1 for a, b in zip(first, last))
    # NBT 包含显式空气，每格都会分配标签；整张套件板应按单件拆分。
    if size[0] * size[1] * size[2] > 500_000:
        raise ValueError("单件最多导出 50 万格；请按独立构件选区")
    if output.exists():
        raise FileExistsError(f"候选模板已存在：{output}")
    if not source.is_dir() and not source.is_file():
        raise ValueError(f"存档不存在：{source}")
    # 输出写到存档外，避免在素材原件留下生成文件或误覆盖 region 数据。
    if source.is_dir() and output.resolve().is_relative_to(source.resolve()):
        raise ValueError("输出必须位于存档目录外")

    with ExitStack() as stack:
        read = lambda path: (source / path).read_bytes()
        if not source.is_dir():
            archive = stack.enter_context(ZipFile(source))
            candidates = [p for p in archive.namelist() if p == "level.dat" or p.endswith("/level.dat")]
            if len(candidates) != 1:
                raise ValueError("ZIP 必须只包含一个存档")
            prefix = candidates[0][:-len("level.dat")]
            read = lambda path: archive.read(prefix + path)

        level = nbt.File.parse(io.BytesIO(gzip.decompress(read("level.dat"))))["Data"]
        generator = level["WorldGenSettings"]["dimensions"]["minecraft:overworld"]["generator"]
        version = int(level["DataVersion"])
        if generator["type"] != "minecraft:flat" or version != 3955:
            raise ValueError(f"只支持 Java 1.21.1 主世界超平坦存档，DataVersion={version}")

        @lru_cache(maxsize=12)
        def region(rx, rz):
            return read(f"region/r.{rx}.{rz}.mca")

        def chunk(cx, cz):
            data = region(cx // 32, cz // 32)
            slot = 4 * (cx % 32 + (cz % 32) * 32)
            entry = struct.unpack_from(">I", data, slot)[0]
            sector, count = entry >> 8, entry & 255
            if sector < 2 or not count or (sector + count) * 4096 > len(data):
                raise ValueError(f"区块缺失或位置表损坏：({cx}, {cz})")
            offset = sector * 4096
            length, kind = struct.unpack_from(">IB", data, offset)
            decode = {1: gzip.decompress, 2: zlib.decompress, 3: bytes}.get(kind)
            if not decode or not 1 < length <= count * 4096 - 4:
                raise ValueError(f"区块压缩格式不支持或长度损坏：({cx}, {cz})，类型 {kind}")
            tag = nbt.File.parse(io.BytesIO(decode(data[offset + 5:offset + 4 + length])))
            if (tag["xPos"], tag["zPos"]) != (cx, cz) or tag["Status"] != "minecraft:full":
                raise ValueError(f"拒绝把错位或未生成完成的区块补成空气：({cx}, {cz})")
            if tag["DataVersion"] != version:
                raise ValueError(f"区块未升级到存档版本：({cx}, {cz})，DataVersion={tag['DataVersion']}")
            return tag

        air = nbt.Compound({"Name": nbt.String("minecraft:air")})
        palette, state_ids, blocks = [], {}, []
        non_air = payload_count = 0
        x0, y0, z0 = first
        x1, y1, z1 = last
        # 按区块扫一次方块实体和截面调色板；负坐标使用地板除法和非负余数。
        for cz in range(z0 // 16, z1 // 16 + 1):
            for cx in range(x0 // 16, x1 // 16 + 1):
                tag = chunk(cx, cz)
                sections = {int(s["Y"]): s["block_states"] for s in tag["sections"] if "block_states" in s}
                entities = {tuple(int(b[k]) for k in "xyz"): b for b in tag.get("block_entities", ())}
                for y in range(y0, y1 + 1):
                    section = sections.get(y // 16)
                    states = section["palette"] if section is not None else (air,)
                    bits = max(4, (len(states) - 1).bit_length())
                    per_word = 64 // bits
                    for z in range(max(z0, cz * 16), min(z1 + 1, cz * 16 + 16)):
                        for x in range(max(x0, cx * 16), min(x1 + 1, cx * 16 + 16)):
                            i = (y % 16 * 16 + z % 16) * 16 + x % 16
                            state = states[0] if len(states) == 1 else states[
                                (int(section["data"][i // per_word]) >> (i % per_word * bits)) & ((1 << bits) - 1)]
                            key = (str(state["Name"]), tuple(sorted(state.get("Properties", {}).items())))
                            if key not in state_ids:
                                state_ids[key] = len(palette)
                                palette.append(state)
                            non_air += key[0] not in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air")
                            block = nbt.Compound({"pos": nbt.List[nbt.Int]([x - x0, y - y0, z - z0]),
                                                  "state": nbt.Int(state_ids[key])})
                            if (payload := entities.get((x, y, z))) is not None:
                                # 原版模板用局部 pos 标定位置；原存档坐标和未加载标记不复制。
                                block["nbt"] = nbt.Compound({k: v for k, v in payload.items()
                                                            if k not in ("x", "y", "z", "keepPacked")})
                                payload_count += 1
                            blocks.append(block)

        if not non_air:
            raise ValueError("选区没有建筑方块")
        template = nbt.File({"DataVersion": nbt.Int(version), "size": nbt.List[nbt.Int](size),
                             "palette": nbt.List[nbt.Compound](palette), "blocks": nbt.List[nbt.Compound](blocks),
                             "entities": nbt.List[nbt.Compound]()})
        # 独占创建，确保两次导出不会覆盖已确认的候选。
        with output.open("xb") as stream:
            with gzip.GzipFile(filename="", fileobj=stream, mode="wb", mtime=0) as target:
                template.write(target)
    print(f"{output}：原点={first} 尺寸={size} 非空气={non_air} 状态={len(palette)} 方块实体={payload_count}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="已退出的存档目录或单存档 ZIP")
    parser.add_argument("output", type=Path, help="存档目录外的新 .nbt 文件")
    parser.add_argument("--min", type=int, nargs=3, required=True, metavar=("X", "Y", "Z"))
    parser.add_argument("--max", type=int, nargs=3, required=True, metavar=("X", "Y", "Z"))
    args = parser.parse_args()
    try:
        export(args.source, args.output, tuple(args.min), tuple(args.max))
    except (KeyError, IndexError, OSError, ValueError, struct.error, zlib.error) as error:
        parser.exit(1, f"导出失败：{error}\n")
