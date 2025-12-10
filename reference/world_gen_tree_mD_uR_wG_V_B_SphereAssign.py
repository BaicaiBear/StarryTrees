import numpy as np
import matplotlib.pyplot as plt
from matplotlib.collections import LineCollection, PolyCollection, PatchCollection
from matplotlib.patches import Circle, Patch
from matplotlib.colors import ListedColormap
from scipy.spatial import Delaunay, Voronoi
import heapq
import time
import json
import os

# --- Configuration ---
RANDOM_SEED = 420
NUM_POINTS = 100000 # Reduced slightly for visualization clarity, increase back to 10000 if needed
RADIUS = 10000
MIN_DIST = 30
NUM_ROOTS = 10
HEIGHT_MIN, HEIGHT_MAX = -32, 310
ROOT_H_MIN, ROOT_H_MAX = 63, 196
SLOPE_MIN, SLOPE_MAX = -0.125, 0.125
RIVER_WIDTH = 200


# Noise Config
NOISE_SCALE = 4.0 

# --- Biome Definitions ---
# Note: 'name' must match JSON filenames
BIOME_DATA = [
    {"name": "warm_ocean",        "h": [40, 200],   "t": [5, 30],   "w": [60, 100], "color": "#1f77b4"}, # Blue
    {"name": "forest",       "h": [100, 200],  "t": [10, 30],  "w": [40, 80], "color": "#2ca02c"}, # Green
    {"name": "deep_frozen_ocean", "h": [40, 80],   "t": [-10, 5],  "w": [60, 100], "color": "#f0f8ff"}, # AliceBlue
    {"name": "swamp",          "h": [50, 70],   "t": [10, 25],  "w": [50, 80],  "color": "#8b4513"}, # SaddleBrown
    {"name": "snowy_taiga", "h": [80, 200],  "t": [-10, 10], "w": [30, 100], "color": "#006400"}, # DarkGreen
    {"name": "lush_caves",         "h": [-64, 40],  "t": [-10, 35], "w": [10, 100], "color": "#000000"}, # Black
    {"name": "desert",       "h": [50, 320],  "t": [20, 35],  "w": [0, 40],   "color": "#ffd700"}, # Gold
    {"name": "stony_peaks",  "h": [250, 320],  "t": [10, 30],  "w": [40, 100],  "color": "#7c7c7c"}, # Grey
    {"name": "frozen_peaks", "h": [250, 320],  "t": [-10, 10],  "w": [30, 100],  "color": "#add8e6"} # LightBlue
]

# --- Helper: Generate Dummy JSONs ---
# In a real scenario, you would provide these files. This ensures the script runs now.
def generate_dummy_configs():
    print("Generating dummy biome configuration files...")
    common_types = [
        {"id": "essential/stone", "size": [10, 30]},
        {"id": "essential/dirt", "size": [5, 15]},
        {"id": "wood/oak", "size": [8, 20]},
        {"id": "mineral/gold", "size": [2, 5]}
    ]
    
    for biome in BIOME_DATA:
        fname = f"{biome['name']}.json"
        if not os.path.exists(fname):
            # Create random variations for dummy data
            configs = []
            leftover_prob = 1.0
            for i, t in enumerate(common_types):
                w = np.random.uniform(0.1, 0.3)
                if i == len(common_types) - 1: w = leftover_prob # Ensure sum is approx 1
                else: leftover_prob -= w
                
                configs.append({
                    "id": t["id"],
                    "size": t["size"],
                    "normalized_weight": max(0, w)
                })
            # Normalize exactly
            total = sum(c['normalized_weight'] for c in configs)
            for c in configs: c['normalized_weight'] /= total
            
            with open(fname, 'w') as f:
                json.dump(configs, f, indent=2)

# --- Config Loader ---
def load_biome_configs():
    configs = {}
    for biome in BIOME_DATA:
        fname = f"{biome['name']}.json"
        try:
            with open(fname, 'r') as f:
                data = json.load(f)
                # Pre-process for fast selection
                ids = [item['id'] for item in data]
                sizes = [item['size'] for item in data]
                weights = [item['normalized_weight'] for item in data]
                # Re-normalize weights to be safe for numpy
                weights = np.array(weights)
                weights /= weights.sum()
                
                configs[biome['name']] = {
                    'ids': ids,
                    'sizes': sizes,
                    'weights': weights
                }
        except FileNotFoundError:
            print(f"Warning: {fname} not found. Skipping.")
            configs[biome['name']] = None
    return configs

# --- Sphere Assignment Logic ---
def assign_spheres(valid_indices, biome_indices, heights, config_map, seed):
    rng = np.random.RandomState(seed)
    
    assigned_types = np.empty(len(heights), dtype=object)
    assigned_radii = np.zeros(len(heights), dtype=float)
    
    print("Assigning Spheres...")
    
    for idx_in_valid, pt_idx in enumerate(valid_indices):
        b_idx = biome_indices[idx_in_valid]
        biome_name = BIOME_DATA[b_idx]['name']
        cfg = config_map.get(biome_name)
        
        if cfg:
            # Random Choice based on weight
            choice_idx = rng.choice(len(cfg['ids']), p=cfg['weights'])
            
            type_id = cfg['ids'][choice_idx]
            min_s, max_s = cfg['sizes'][choice_idx]
            
            # Random Radius
            radius = rng.randint(min_s, max_s + 1)
            
            assigned_types[idx_in_valid] = type_id
            assigned_radii[idx_in_valid] = radius
            
            # # Decrease Height
            # heights[idx_in_valid] -= radius
        else:
            assigned_types[idx_in_valid] = "unknown"
            assigned_radii[idx_in_valid] = 5
            
    return assigned_types, assigned_radii

# --- Robust Perlin Noise ---
def perlin_noise_2d(x, y, seed=0):
    rng = np.random.RandomState(seed)
    p = np.arange(256, dtype=int)
    rng.shuffle(p)
    p = np.stack([p, p]).flatten()
    
    xi = np.floor(x).astype(int)
    yi = np.floor(y).astype(int)
    xf = x - xi
    yf = y - yi
    xi = xi % 256
    yi = yi % 256
    
    def fade(t): return 6 * t**5 - 15 * t**4 + 10 * t**3
    u = fade(xf); v = fade(yf)
    
    n_grads = 256
    angles = 2 * np.pi * np.arange(n_grads) / n_grads
    grads = np.stack([np.cos(angles), np.sin(angles)], axis=1)
    
    def grad(hash_val, x, y):
        h = hash_val % n_grads
        g = grads[h]
        return g[:, 0] * x + g[:, 1] * y

    aa = p[p[xi] + yi]
    ab = p[p[xi] + yi + 1]
    ba = p[p[xi + 1] + yi]
    bb = p[p[xi + 1] + yi + 1]
    
    res = (1-u)*(1-v)*grad(aa, xf, yf) + (1-u)*v*grad(ab, xf, yf-1) + \
          u*(1-v)*grad(ba, xf-1, yf) + u*v*grad(bb, xf-1, yf-1)
    return res

def fractal_noise(x, y, seed=0, octaves=3, persistence=0.5):
    total = np.zeros_like(x)
    frequency = 1.0; amplitude = 1.0; max_value = 0.0; current_seed = seed
    for _ in range(octaves):
        total += perlin_noise_2d(x * frequency, y * frequency, current_seed) * amplitude
        max_value += amplitude
        amplitude *= persistence
        frequency *= 2.0
        current_seed += 1
    return total / max_value

# --- Biome Calculation Algorithm ---
def calculate_biomes(heights, temps, humidities):
    num_points = len(heights)
    attributes = np.vstack((heights, temps, humidities)).T
    best_biome_indices = np.zeros(num_points, dtype=int)
    min_scores = np.full(num_points, np.inf)
    
    print("Calculating Biomes...")
    for i, biome in enumerate(BIOME_DATA):
        ranges = [biome['h'], biome['t'], biome['w']]
        m = np.array([r[0] for r in ranges])
        n = np.array([r[1] for r in ranges])
        
        dist_lower = np.maximum(0, m - attributes)
        dist_upper = np.maximum(0, attributes - n)
        dists = dist_lower + dist_upper
        scores = np.sum(dists**2, axis=1)
        
        mask = scores < min_scores
        min_scores[mask] = scores[mask]
        best_biome_indices[mask] = i
    return best_biome_indices

# --- Graph Generation ---
def generate_points_poisson_approx(n, radius, min_dist, seed):
    np.random.seed(seed)
    points = []
    cell_size = min_dist / 1.41421356
    grid = {} 
    max_attempts = n * 50
    attempts = 0
    while len(points) < n and attempts < max_attempts:
        r_rand = radius * np.sqrt(np.random.rand())
        theta = np.random.rand() * 2 * np.pi
        x, y = r_rand * np.cos(theta), r_rand * np.sin(theta)
        gx, gy = int(x / cell_size), int(y / cell_size)
        valid = True
        for dx in [-1, 0, 1]:
            for dy in [-1, 0, 1]:
                if (gx + dx, gy + dy) in grid:
                    for px, py in grid[(gx + dx, gy + dy)]:
                        if (x - px)**2 + (y - py)**2 < min_dist**2:
                            valid = False; break
                if not valid: break
            if not valid: break
        if valid:
            points.append([x, y])
            if (gx, gy) not in grid: grid[(gx, gy)] = []
            grid[(gx, gy)].append((x, y))
        attempts += 1
    return np.array(points)

def build_graph(points):
    tri = Delaunay(points)
    adj = {i: [] for i in range(len(points))}
    for simplex in tri.simplices:
        p = sorted(simplex)
        for u, v in [(p[0], p[1]), (p[1], p[2]), (p[0], p[2])]:
            dist = np.linalg.norm(points[u] - points[v])
            adj[u].append((v, dist)); adj[v].append((u, dist))
    return adj, tri

def process_rivers(points, adj, tri, num_roots, seed):
    np.random.seed(seed)
    centroids = points[np.random.choice(len(points), num_roots, replace=False)]
    for _ in range(5): 
        dists = np.linalg.norm(points[:, None] - centroids[None, :], axis=2)
        labels = np.argmin(dists, axis=1)
        centroids = np.array([points[labels==k].mean(axis=0) if np.any(labels==k) else points[np.random.choice(len(points))] for k in range(num_roots)])
    roots = [np.argmin(np.linalg.norm(points - c, axis=1)) for c in centroids]
    
    tree_ids = np.full(len(points), -1)
    pq = []
    for r in roots:
        tree_ids[r] = r
        for v, d in adj[r]: heapq.heappush(pq, (d, r, v))
    while pq:
        d, u, v = heapq.heappop(pq)
        if tree_ids[v] == -1:
            tree_ids[v] = tree_ids[u]
            for n, w in adj[v]: 
                if tree_ids[n] == -1: heapq.heappush(pq, (w, v, n))
                
    boundaries = set()
    processed = set()
    for u in adj:
        for v, _ in adj[u]:
            key = tuple(sorted((u, v)))
            if key not in processed:
                processed.add(key)
                if tree_ids[u] != -1 and tree_ids[v] != -1 and tree_ids[u] != tree_ids[v]:
                    boundaries.add(key)
    
    removed = set()
    # Simplified erosion logic for brevity
    lines_arr = []
    for simplex in tri.simplices:
        edges = [tuple(sorted((simplex[0], simplex[1]))), tuple(sorted((simplex[1], simplex[2]))), tuple(sorted((simplex[0], simplex[2])))]
        bounds = [e for e in edges if e in boundaries]
        if len(bounds) >= 2:
            mid1 = (points[bounds[0][0]] + points[bounds[0][1]]) / 2
            mid2 = (points[bounds[1][0]] + points[bounds[1][1]]) / 2
            lines_arr.append([mid1, mid2])
            
    if lines_arr:
        lines_arr = np.array(lines_arr)
        # Vectorized river distance check
        P = points[:, np.newaxis, :]
        A = lines_arr[np.newaxis, :, 0, :]
        B = lines_arr[np.newaxis, :, 1, :]
        AB = B - A; AP = P - A
        dot_AP_AB = np.sum(AP * AB, axis=2)
        dot_AB_AB = np.sum(AB * AB, axis=2)
        t = np.clip(dot_AP_AB / (dot_AB_AB + 1e-8), 0, 1)
        C = A + t[..., np.newaxis] * AB
        dists_sq = np.sum((P - C)**2, axis=2)
        min_dists = np.sqrt(np.min(dists_sq, axis=1))
        removed = set(np.where(min_dists < RIVER_WIDTH)[0])

    final_roots = {}
    for r in roots:
        if r in removed:
            candidates = [i for i in np.where(tree_ids == r)[0] if i not in removed]
            if candidates: final_roots[r] = candidates[np.argmin(np.linalg.norm(points[candidates] - points[r], axis=1))]
        else: final_roots[r] = r
        
    return final_roots, tree_ids, removed, lines_arr, adj

def build_forest(points, adj, roots, old_ids, removed):
    edges = []
    new_ids = np.full(len(points), -1)
    parent = {i: None for i in range(len(points))}
    invalid = set(removed)
    
    for old_r, new_r in roots.items():
        pq = []
        new_ids[new_r] = old_r
        for n, d in adj[new_r]:
            if n not in invalid and old_ids[n] == old_r: heapq.heappush(pq, (d, new_r, n))
        while pq:
            d, u, v = heapq.heappop(pq)
            if new_ids[v] == -1:
                new_ids[v] = old_r
                edges.append((u, v))
                parent[v] = u
                for n, w in adj[v]:
                    if n not in invalid and new_ids[n] == -1 and old_ids[n] == old_r:
                        heapq.heappush(pq, (w, v, n))
    return edges, parent, new_ids

def calc_heights(points, roots, parent, mask, seed):
    np.random.seed(seed + 1)
    heights = np.full(len(points), HEIGHT_MIN - 10.0)
    children = {i: [] for i in range(len(points))}
    for c, p in parent.items():
        if p is not None: children[p].append(c)
        
    stack = list(roots.values())
    for r in stack: heights[r] = np.random.randint(ROOT_H_MIN, ROOT_H_MAX)
    
    while stack:
        u = stack.pop()
        for v in children[u]:
            dist = np.linalg.norm(points[u] - points[v])
            while True:
                h = heights[u] + np.random.uniform(SLOPE_MIN, SLOPE_MAX) * dist
                if HEIGHT_MIN <= h <= HEIGHT_MAX:
                    heights[v] = h
                    break
            stack.append(v)
    return heights

# --- New: Plotting the Spheres ---
def plot_sphere_map(points, radii, types, biome_indices, filename="map_spheres.png"):
    print(f"Plotting Spheres to {filename}...")
    fig, ax = plt.subplots(figsize=(12, 12))
    
    # Create patches
    patches = []
    colors = []
    
    # Use Biome Colors for the spheres
    biome_colors_hex = [b['color'] for b in BIOME_DATA]
    
    for i in range(len(points)):
        p = points[i]
        r = radii[i]
        if r > 0: # Only plot valid spheres
            patches.append(Circle((p[0], p[1]), r))
            colors.append(biome_indices[i])
            
    # Create collection
    cmap = ListedColormap(biome_colors_hex)
    pc = PatchCollection(patches, cmap=cmap, alpha=0.9, linewidths=0.1, edgecolors='black')
    pc.set_array(np.array(colors))
    pc.set_clim(0, len(BIOME_DATA) - 1)
    
    ax.add_collection(pc)
    
    # Setup axis
    ax.add_patch(Circle((0,0), RADIUS, fill=False, lw=2, ls='--'))
    ax.set_xlim(-RADIUS-200, RADIUS+200)
    ax.set_ylim(-RADIUS-200, RADIUS+200)
    ax.set_aspect('equal')
    ax.axis('off')
    ax.set_title("Generated Spheres (Actual Size)", fontsize=16)
    
    # Legend
    legend_elements = [Patch(facecolor=b['color'], edgecolor='black', label=b['name']) for b in BIOME_DATA]
    ax.legend(handles=legend_elements, loc='upper right', title="Biomes")
    
    plt.savefig(filename, bbox_inches='tight', dpi=300)
    plt.close()

# --- New: Data Export ---
def save_world_data(filename, points, valid_mask, edges, heights, biome_indices, types, radii):
    print(f"Saving world data to {filename}...")
    
    # 1. Prepare Voronoi
    vor = Voronoi(points)
    vor_vertices = np.ceil(vor.vertices).astype(int).tolist()
    
    nodes_data = []
    node_pos_map = {}  # Map node ID to its calculated 3D position
    
    valid_indices = np.where(valid_mask)[0]
    
    for i, idx in enumerate(valid_indices):
        b_idx = int(biome_indices[i])
        
        # Get Voronoi region vertices for this point
        region_idx = vor.point_region[idx]
        region_verts_indices = vor.regions[region_idx]
        
        # Ensure region is valid and closed
        if -1 in region_verts_indices or len(region_verts_indices) == 0:
            poly = []
        else:
            poly = [vor_vertices[v] for v in region_verts_indices]
        
        pos = [
            int(np.ceil(points[idx, 0])), 
            int(np.ceil(heights[i]) - np.ceil(radii[i])),
            int(np.ceil(points[idx, 1]))
        ]
        node_pos_map[int(idx)] = [
            int(np.ceil(points[idx, 0])), 
            int(np.ceil(heights[i])),
            int(np.ceil(points[idx, 1]))
        ]
        
        node_entry = {
            "id": int(idx),
            "pos": pos,
            "biome": BIOME_DATA[b_idx]['name'],
            "type": types[i],
            "radius": int(np.ceil(radii[i])),
            "voronoi_region": poly # List of [x,y]
        }
        nodes_data.append(node_entry)
        
    # --- Save Node Data ---
    out_data = {
        "metadata": {
            "seed": RANDOM_SEED,
            "num_nodes": len(nodes_data),
            "radius_map": RADIUS
        },
        "nodes": nodes_data
    }
    
    with open(filename, 'w') as f:
        json.dump(out_data, f)
    print(f"Node data saved to {filename}.")

    # --- Save Edge Data ---
    edges_data = []
    valid_set = set(valid_indices)
    for u, v in edges:
        if u in valid_set and v in valid_set:
            pos_u = node_pos_map.get(int(u))
            pos_v = node_pos_map.get(int(v))
            if pos_u and pos_v:
                edges_data.append([pos_u, pos_v])

    # Isolate edge data into its own file
    base_name = os.path.splitext(filename)[0]
    edge_filename = f"{base_name}_edges.json"
    
    print(f"Saving edge data to {edge_filename}...")
    with open(edge_filename, 'w') as f:
        json.dump({"edges": edges_data}, f)
        
    print("Save complete.")

# --- Main ---
def main():
    start_time = time.time()
    
    # 0. Setup Dummy Configs (so the script runs without external files)
    generate_dummy_configs()
    
    # 1. Geometry & Topology
    points = generate_points_poisson_approx(NUM_POINTS, RADIUS, MIN_DIST, RANDOM_SEED)
    adj, tri = build_graph(points)
    roots, tree_ids, removed, river_lines, _ = process_rivers(points, adj, tri, NUM_ROOTS, RANDOM_SEED)
    edges, parent_map, final_ids = build_forest(points, adj, roots, tree_ids, removed)
    
    # 2. Properties
    valid_mask = final_ids != -1
    valid_indices = np.where(valid_mask)[0]
    valid_points = points[valid_mask]
    
    heights = calc_heights(points, roots, parent_map, valid_mask, RANDOM_SEED)
    valid_heights = heights[valid_mask] # Work on copy for modifications
    
    norm_x = (valid_points[:, 0] + RADIUS) / (2 * RADIUS) * NOISE_SCALE
    norm_y = (valid_points[:, 1] + RADIUS) / (2 * RADIUS) * NOISE_SCALE
    
    raw_temp = fractal_noise(norm_x, norm_y, seed=RANDOM_SEED+101, octaves=2)
    temperature = np.interp(raw_temp, [-0.6, 0.6], [-10, 40])
    
    raw_hum = fractal_noise(norm_x + 12.0, norm_y - 12.0, seed=RANDOM_SEED+202, octaves=3)
    humidity = np.interp(raw_hum, [-0.6, 0.6], [0, 100])
    
    # 3. Biome Determination
    biome_indices = calculate_biomes(valid_heights, temperature, humidity)
    
    # 4. Load Configs and Assign Spheres
    biome_configs = load_biome_configs()
    sphere_types, sphere_radii = assign_spheres(valid_indices, biome_indices, valid_heights, biome_configs, RANDOM_SEED)
    
    # 5. Plotting
    plot_sphere_map(valid_points, sphere_radii, sphere_types, biome_indices, "map_spheres.png")
    
    # 6. Export
    save_world_data("world_data.json", points, valid_mask, edges, valid_heights, biome_indices, sphere_types, sphere_radii)
    
    print(f"Done in {time.time() - start_time:.2f}s")

if __name__ == "__main__":
    main()