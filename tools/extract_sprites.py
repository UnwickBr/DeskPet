#!/usr/bin/env python3
"""Recorta a spritesheet do coelho em quadros PNG transparentes para o DeskPet de PC.

Uso:
    python3 tools/extract_sprites.py <spritesheet.png> [pasta_saida]

A spritesheet deve ter fundo liso e uma animação por linha, nesta ordem:
    walk (8), walk fase 2 (8), jump (6), sleep (4), sniff (5), scared (6), land (6)
Linhas com o mesmo nome viram uma animação só (walk_0..walk_15).
O texto/rótulos da coluna da esquerda são ignorados.
"""
import sys
from collections import deque
from pathlib import Path

from PIL import Image

ROWS = [("walk", 8), ("walk", 8), ("jump", 6), ("sleep", 4), ("sniff", 5), ("scared", 6), ("land", 6)]
BG_TOLERANCE = 34        # distância de cor até o fundo para ser considerado fundo
LABEL_MAX_X = 130        # componentes inteiramente à esquerda disso são rótulos
CORE_MIN_AREA = 2500     # tamanho mínimo de um coelho (em pixels)
HOLE_MIN_AREA = 4        # buraco de fundo preso dentro do desenho (ex.: entre as patas)
HOLE_TOLERANCE = BG_TOLERANCE
HOLE_MAX_NEIGHBOR_LUM = 110  # só é buraco se estiver cercado de contorno escuro
MIN_EFFECT_AREA = 20     # pedaços soltos menores que isso são sujeira da imagem, não efeitos
ATTACH_MAX_DIST = 45     # efeitos (zzz, tracinhos) a até essa distância entram no quadro
PADDING = 2


EDGE_BAND = 2            # pixels perto do fundo que recebem borda suave
EDGE_FULL_DIST = 110     # a partir dessa distância de cor do fundo o pixel é 100% opaco


def color_dist(a, b):
    return abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])


def split_component(c, W):
    """Divide uma peça com dois coelhos colados na coluna com menos pixels (o ponto de contato)."""
    x0, y0, x1, y1, _, pixels = c
    width = x1 - x0 + 1
    if width < 60:
        return None
    counts = [0] * width
    for i in pixels:
        counts[i % W - x0] += 1
    lo, hi = int(width * 0.3), int(width * 0.7)
    cut = x0 + min(range(lo, hi), key=lambda k: counts[k])
    halves = []
    for side in (lambda x: x < cut, lambda x: x >= cut):
        pts = [i for i in pixels if side(i % W)]
        xs = [i % W for i in pts]
        ys = [i // W for i in pts]
        halves.append([min(xs), min(ys), max(xs), max(ys), len(pts), pts])
    return halves


def edge_pixel(px, is_bg, W, H, x, y, bg):
    """Pixels da borda viram semitransparentes e perdem a cor do fundo misturada (sem halo claro)."""
    c = px[x, y]
    near_bg = False
    for dy in range(-EDGE_BAND, EDGE_BAND + 1):
        ny = y + dy
        if 0 <= ny < H:
            row = ny * W
            for dx in range(-EDGE_BAND, EDGE_BAND + 1):
                nx = x + dx
                if 0 <= nx < W and is_bg[row + nx]:
                    near_bg = True
                    break
        if near_bg:
            break
    if not near_bg:
        return c + (255,)

    # cor "de dentro" mais próxima: o vizinho mais diferente do fundo (normalmente o contorno escuro)
    r = EDGE_BAND + 1
    f, fd = None, -1
    for dy in range(-r, r + 1):
        ny = y + dy
        if not 0 <= ny < H:
            continue
        for dx in range(-r, r + 1):
            nx = x + dx
            if 0 <= nx < W and not is_bg[ny * W + nx]:
                n = px[nx, ny]
                d = color_dist(n, bg)
                if d > fd:
                    f, fd = n, d

    if fd >= 60:
        # c = a*f + (1-a)*fundo  ->  a = projeção de (c - fundo) sobre (f - fundo)
        v = [f[k] - bg[k] for k in range(3)]
        u = [c[k] - bg[k] for k in range(3)]
        a = sum(u[k] * v[k] for k in range(3)) / max(1, sum(vk * vk for vk in v))
    else:  # região clara (rabo/bigode) sem contorno escuro por perto
        a = color_dist(c, bg) / EDGE_FULL_DIST
    a = max(0.0, min(1.0, a))
    if a < 0.06:
        return (0, 0, 0, 0)
    # "desmistura" a cor: c = a*cor_real + (1-a)*fundo
    real = tuple(max(0, min(255, round((c[k] - (1 - a) * bg[k]) / a))) for k in range(3))
    return real + (round(a * 255),)


def main():
    src = Path(sys.argv[1])
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).resolve().parent.parent / "desktop/sprites"
    img = Image.open(src).convert("RGB")
    W, H = img.size
    px = img.load()

    border = [px[x, 0] for x in range(W)] + [px[x, H - 1] for x in range(W)] + \
             [px[0, y] for y in range(H)] + [px[W - 1, y] for y in range(H)]
    bg = tuple(sorted(c[i] for c in border)[len(border) // 2] for i in range(3))

    # 1) fundo = tudo parecido com a cor de fundo e ligado à borda (flood fill)
    is_bg = bytearray(W * H)
    q = deque()
    for x in range(W):
        q.extend(((x, 0), (x, H - 1)))
    for y in range(H):
        q.extend(((0, y), (W - 1, y)))
    while q:
        x, y = q.popleft()
        i = y * W + x
        if is_bg[i] or color_dist(px[x, y], bg) > BG_TOLERANCE:
            continue
        is_bg[i] = 1
        if x > 0: q.append((x - 1, y))
        if x < W - 1: q.append((x + 1, y))
        if y > 0: q.append((x, y - 1))
        if y < H - 1: q.append((x, y + 1))
    # buracos fechados com cor de fundo (ex.: entre as patas)
    bgish = bytearray(W * H)
    for i in range(W * H):
        if not is_bg[i] and color_dist(px[i % W, i // W], bg) <= HOLE_TOLERANCE:
            bgish[i] = 1
    seen = bytearray(W * H)
    for start in range(W * H):
        if not bgish[start] or seen[start]:
            continue
        seen[start] = 1
        group, stack = [], [start]
        while stack:
            i = stack.pop()
            group.append(i)
            x, y = i % W, i // W
            for j in (i - 1 if x > 0 else -1, i + 1 if x < W - 1 else -1, i - W if y > 0 else -1, i + W if y < H - 1 else -1):
                if j >= 0 and bgish[j] and not seen[j]:
                    seen[j] = 1
                    stack.append(j)
        if len(group) < HOLE_MIN_AREA:
            continue
        # vizinhos em volta do aglomerado: buraco de verdade fica cercado pelo contorno escuro;
        # sombra do rabo fica cercada de pelo claro, reflexo da pata de cinza médio
        members_set = set(group)
        lum_sum = lum_n = 0
        for i in group:
            x, y = i % W, i // W
            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if 0 <= nx < W and 0 <= ny < H:
                    j = ny * W + nx
                    if j not in members_set and not is_bg[j]:
                        c = px[nx, ny]
                        lum_sum += 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]
                        lum_n += 1
        if lum_n and lum_sum / lum_n <= HOLE_MAX_NEIGHBOR_LUM:
            for i in group:
                is_bg[i] = 1

    # 2) componentes conectados do que sobrou
    label = [0] * (W * H)
    comps = []  # (x0, y0, x1, y1, area, pixels)
    for start in range(W * H):
        if is_bg[start] or label[start]:
            continue
        cid = len(comps) + 1
        label[start] = cid
        stack = [start]
        pixels = []
        x0 = y0 = 10 ** 9
        x1 = y1 = -1
        while stack:
            i = stack.pop()
            pixels.append(i)
            x, y = i % W, i // W
            x0, x1, y0, y1 = min(x0, x), max(x1, x), min(y0, y), max(y1, y)
            for dy in (-1, 0, 1):
                ny = y + dy
                if not 0 <= ny < H:
                    continue
                for dx in (-1, 0, 1):
                    nx = x + dx
                    if 0 <= nx < W:
                        j = ny * W + nx
                        if not is_bg[j] and not label[j]:
                            label[j] = cid
                            stack.append(j)
        comps.append([x0, y0, x1, y1, len(pixels), pixels])

    comps = [c for c in comps if c[2] > LABEL_MAX_X and c[4] >= MIN_EFFECT_AREA]
    cores = [c for c in comps if c[4] >= CORE_MIN_AREA]
    extras = [c for c in comps if c[4] < CORE_MIN_AREA]

    # 3) agrupa os coelhos em linhas (pela altura do centro) e ordena por x
    cores.sort(key=lambda c: (c[1] + c[3]) / 2)
    rows, cur = [], [cores[0]]
    for c in cores[1:]:
        prev_cy = sum((k[1] + k[3]) / 2 for k in cur) / len(cur)
        if (c[1] + c[3]) / 2 - prev_cy > 70:
            rows.append(cur)
            cur = [c]
        else:
            cur.append(c)
    rows.append(cur)
    want = [n for _, n in ROWS]
    # coelhos encostados viram uma peça só: corta a peça mais larga até a linha ter o número certo
    if len(rows) == len(want):
        for r, n in zip(rows, want):
            while len(r) < n:
                widest = max(r, key=lambda c: c[2] - c[0])
                halves = split_component(widest, W)
                if not halves:
                    break
                r.remove(widest)
                r.extend(halves)
    got = [len(r) for r in rows]
    if got != want:
        sys.exit(f"Esperava {want} quadros por linha, encontrei {got}. Ajuste os parâmetros no topo do script.")

    frames = [sorted(r, key=lambda c: c[0]) for r in rows]
    members = {id(c): [c] for r in frames for c in r}

    # 4) efeitos pequenos (zzz, tracinhos, bigodes soltos) vão para o coelho mais próximo
    def box_dist(a, b):
        dx = max(b[0] - a[2], a[0] - b[2], 0)
        dy = max(b[1] - a[3], a[1] - b[3], 0)
        return max(dx, dy)

    all_cores = [c for r in frames for c in r]
    for e in extras:
        near = [c for c in all_cores if box_dist(e, c) <= ATTACH_MAX_DIST]
        if not near:
            continue
        # entre linhas muito juntas, prefere o coelho cujo centro está mais perto na vertical
        ecy = (e[1] + e[3]) / 2
        best = min(near, key=lambda c: box_dist(e, c) + 0.5 * abs((c[1] + c[3]) / 2 - ecy))
        members[id(best)].append(e)

    # 5) grava cada quadro como PNG transparente
    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("*.png"):
        old.unlink()
    counters = {}
    for (name, _), row in zip(ROWS, frames):
        for core in row:
            n = counters.get(name, 0)
            counters[name] = n + 1
            parts = members[id(core)]
            x0 = min(p[0] for p in parts) - PADDING
            y0 = min(p[1] for p in parts) - PADDING
            x1 = max(p[2] for p in parts) + PADDING
            y1 = max(p[3] for p in parts) + PADDING
            frame = Image.new("RGBA", (x1 - x0 + 1, y1 - y0 + 1), (0, 0, 0, 0))
            fp = frame.load()
            for p in parts:
                for i in p[5]:
                    x, y = i % W, i // W
                    fp[x - x0, y - y0] = edge_pixel(px, is_bg, W, H, x, y, bg)
            frame.save(out / f"{name}_{n}.png")
    print(f"{sum(want)} quadros gravados em {out}")


if __name__ == "__main__":
    main()
