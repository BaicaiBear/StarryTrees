#!/usr/bin/env python3
"""Check frozen infinite macro regions without starting Minecraft."""

import argparse
import gzip
import json
import math
from collections import defaultdict
from pathlib import Path


def verify(directory: Path, closed: bool = False, window_radius=None) -> None:
    regions = {}
    for file in directory.glob("regions/r.*.*.json.gz"):
        with gzip.open(file, "rt") as stream:
            region = json.load(stream)
        key = region["regionX"], region["regionZ"]
        if window_radius is not None and max(abs(key[0]), abs(key[1])) > window_radius:
            continue
        assert key not in regions, key
        assert region["version"] == 11, key
        regions[key] = region
    assert regions, f"No snapshots in {directory}"

    all_nodes = {}
    roots = {}
    endpoint_nodes = defaultdict(list)
    total_edges = 0
    cross_edges = []
    for key, region in regions.items():
        nodes = region["nodes"]
        edges = region["edges"]
        assert [n["id"] for n in nodes] == list(range(len(nodes))), key
        parent = list(range(len(nodes)))

        def find(i):
            while i != parent[i]:
                parent[i] = parent[parent[i]]
                i = parent[i]
            return i

        internal = 0
        for edge in edges:
            a, b = edge["startId"], edge["endId"]
            assert 0 <= a < len(nodes), (key, a)
            horizontal = math.hypot(edge["points"][0][0] - edge["points"][1][0],
                                    edge["points"][0][2] - edge["points"][1][2])
            assert 0 < horizontal <= 192, (key, horizontal)
            if b < 0:
                cross_edges.append((key, edge))
                continue
            assert 0 <= b < len(nodes), (key, b)
            assert find(a) != find(b), (key, "cycle")
            parent[find(a)] = find(b)
            internal += 1
        assert internal == len(nodes) - 1, (key, len(nodes), internal)
        assert len({find(i) for i in range(len(nodes))}) == 1, key
        for node in nodes:
            all_nodes[key, node["id"]] = node
            top = (int(node["pos"][0]), int(node["pos"][1] + node["radius"]),
                   int(node["pos"][2]))
            endpoint_nodes[top].append((key, node["id"]))
        roots[key] = (key, 0)
        total_edges += internal

    # Compare all loaded nodes, including nodes across macro-region borders.
    occupied = defaultdict(list)
    for identity, node in all_nodes.items():
        x, y, z = node["pos"]
        cell = (int(x // 80), int(y // 80), int(z // 80))
        for dx in range(-2, 3):
            for dy in range(-2, 3):
                for dz in range(-2, 3):
                    for other_id in occupied[cell[0] + dx, cell[1] + dy, cell[2] + dz]:
                        other = all_nodes[other_id]
                        distance = math.sqrt(sum((a - b) ** 2 for a, b in zip(node["pos"], other["pos"])))
                        assert distance >= node["radius"] + other["radius"] + 2, (
                            identity, other_id, distance)
        occupied[cell].append(identity)

    def bridge_hits_sphere(a, b, node):
        dx, dz = b[0] - a[0], b[2] - a[2]
        horizontal_sq = dx * dx + dz * dz
        if horizontal_sq < 1:
            return False
        x, y, z = node["pos"]
        projection = ((x - a[0]) * dx + (z - a[2]) * dz) / horizontal_sq
        if projection < -0.1 or projection > 1.1:
            return False
        horizontal = math.sqrt(horizontal_sq)
        sag = min(12, horizontal * .08)
        if abs(b[1] - a[1]) > horizontal * .5:
            sag = min(sag, 3)
        for shift in (-.08, 0, .08):
            t = max(0, min(1, projection + shift))
            bx, bz = a[0] + t * dx, a[2] + t * dz
            by = a[1] + (b[1] - a[1]) * t + sag * (
                math.cosh(2 * t - 1) - math.cosh(1)) / (math.cosh(1) - 1)
            if math.sqrt((x - bx) ** 2 + (y - by) ** 2 + (z - bz) ** 2) < node["radius"] + 3:
                return True
        return False

    for region_key, region in regions.items():
        for edge in region["edges"]:
            a, b = edge["points"]
            excluded = {(region_key, edge["startId"])}
            if edge["endId"] >= 0:
                excluded.add((region_key, edge["endId"]))
            else:
                excluded.update(endpoint_nodes[tuple(b)])
            ranges = [range(int((min(a[i], b[i]) - (55 if i == 1 else 42)) // 80),
                            int((max(a[i], b[i]) + 42) // 80) + 1) for i in (0, 1, 2)]
            for cx in ranges[0]:
                for cy in ranges[1]:
                    for cz in ranges[2]:
                        for identity in occupied[cx, cy, cz]:
                            if identity not in excluded:
                                assert not bridge_hits_sphere(a, b, all_nodes[identity]), (
                                    region_key, edge["startId"], edge["endId"], identity)

    # Each outgoing edge must hit the neighboring region's port exactly.
    macro_parent = {key: key for key in regions}

    def find_macro(key):
        while macro_parent[key] != key:
            macro_parent[key] = macro_parent[macro_parent[key]]
            key = macro_parent[key]
        return key

    matched = 0
    for source, edge in cross_edges:
        matches = [target for target in endpoint_nodes[tuple(edge["points"][1])]
                   if target[0] != source]
        if not matches:
            continue
        assert len(matches) == 1, (source, matches)
        target = matches[0][0]
        assert abs(source[0] - target[0]) + abs(source[1] - target[1]) == 1
        assert find_macro(source) != find_macro(target), (source, target, "macro cycle")
        macro_parent[find_macro(source)] = find_macro(target)
        matched += 1
    if closed:
        assert matched == len(regions) - 1, (matched, len(regions))
        assert len({find_macro(key) for key in regions}) == 1
    print(f"{directory.name}: {len(regions)} regions, {len(all_nodes)} spheres, "
          f"{total_edges} local bridges, {matched}/{len(cross_edges)} linked borders; "
          f"no sphere overlap or local cycles")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("world_starrytrees", type=Path)
    parser.add_argument("--closed", action="store_true", help="Require the loaded region window to form one tree")
    parser.add_argument("--window-radius", type=int, help="Only inspect macro regions this far from the origin")
    args = parser.parse_args()
    verify(args.world_starrytrees / "infinite-overworld", args.closed, args.window_radius)
    verify(args.world_starrytrees / "infinite-nether", args.closed, args.window_radius)
