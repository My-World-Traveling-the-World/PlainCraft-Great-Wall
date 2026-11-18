"""从已核验的结构 NBT 生成长城稀疏素材；原始导出仍是空气与选区的取证依据。"""

import argparse
from collections import Counter
import gzip
import hashlib
import json
from pathlib import Path

import nbtlib as nbt


AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
CONTAINERS = {"minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel"}
# 只复制建筑展示数据；燃烧、冷却、漏斗库存和源存档的战利品都不随建筑迁移。
DECORATION = {
    "minecraft:banner": {"patterns", "CustomName"},
    "minecraft:sign": {"front_text", "back_text", "is_waxed"},
    "minecraft:lectern": {"Book", "Page"},
    "minecraft:chiseled_bookshelf": {"Items", "last_interacted_slot"},
    "minecraft:decorated_pot": {"sherds"},
    "minecraft:skull": {"profile", "note_block_sound"},
    **dict.fromkeys(("minecraft:bed", "minecraft:bell", "minecraft:campfire", "minecraft:furnace",
                     "minecraft:hopper", "minecraft:conduit", "minecraft:daylight_detector"), set()),
    **dict.fromkeys(CONTAINERS, set()),
}


def prepare(source, output):
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    output.mkdir(parents=True, exist_ok=True)
    provenance = {}
    for entry in manifest["templates"]:
        name = entry["file"]
        original = source / name
        source_hash = hashlib.sha256(original.read_bytes()).hexdigest()
        if source_hash != entry["sha256"]:
            raise ValueError(f"素材摘要与导出清单不符：{name}")
        tag = nbt.load(original)
        palette = [str(state["Name"]) for state in tag["palette"]]
        counts = Counter()
        solid = []
        if len(tag["blocks"]) != entry["volume"] or tag["entities"] or int(tag["DataVersion"]) != 3955:
            raise ValueError(f"不支持的导出内容：{name}")
        for block in tag["blocks"]:
            block_id = palette[int(block["state"])]
            if block_id in AIR:
                if "nbt" in block:
                    raise ValueError(f"空气格包含实体：{name} {block['pos']}")
                continue
            if "nbt" in block:
                entity = block["nbt"]
                kind = str(entity["id"])
                keep = DECORATION[kind]
                counts[kind] += 1
                # 箱桶即使原样为空也保留实体身份，正式计划才能初始化原版延迟掉落。
                block["nbt"] = nbt.Compound({key: value for key, value in entity.items()
                                             if key == "id" or key in keep})
            solid.append(block)
        if len(solid) != entry["non_air_blocks"] or sum(counts.values()) != entry["block_entity_count"]:
            raise ValueError(f"素材内容数量与导出清单不符：{name}")
        tag["blocks"] = nbt.List[nbt.Compound](solid)
        target = output / name
        with target.open("wb") as stream:
            with gzip.GzipFile(filename="", fileobj=stream, mode="wb", mtime=0) as compressed:
                tag.write(compressed)
        provenance[name] = {
            "source_sha256": source_hash, "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
            "bounds_inclusive": entry["bounds_inclusive"], "size_xyz": entry["size_xyz"],
            "non_air": len(solid), "block_entities": sum(counts.values()),
            "loot_containers": sum(counts[t] for t in CONTAINERS),
            "wall_end_marks": entry.get("interface_edge_quartz_positions_xyz", {}),
        }
    (output / "provenance.json").write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + "\n",
                                            encoding="utf-8", newline="\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="包含 manifest.json 和五件已核验 NBT 的目录")
    parser.add_argument("output", type=Path, help="稀疏素材输出目录")
    args = parser.parse_args()
    prepare(args.source, args.output)
