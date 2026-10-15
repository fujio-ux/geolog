#!/usr/bin/env python3
"""背景の日本地図を作る。町字の代表点（約 21 万点）を緑の点で打つと、日本の形が浮かび上がる（点描の地図）。

出力
  app/src/main/res/drawable-nodpi/japan_dots.png   透明の背景に緑の点（幅 720）
  app/src/main/java/jp/hasami/geolog/MapProj.java  緯度経度 → 絵の上の位置（ピンを打つのに使う。絵と同じ式）

配置：本土（北海道〜九州・種子島）を大きく、小笠原を右下の枠に入れる（太平洋の空いた所）。
  南西諸島（沖縄・奄美）は描かない（ユーザーの希望。左上の日本海の空いた所は、画面で住所を重ねて出すのに使う）。
  python3 tools/gen_map.py
"""
import csv
import math
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(HERE, '..', 'app', 'src', 'main')
TOWNS = os.path.expanduser('~/Documents/reverse-geocoding/data/towns.csv')
FONT = os.path.join(APP, 'res', 'font', 'toro_glitch_h1.otf')   # 瀞ノグリッチ黒体 H1（英数字だけ）

W = 720
MARGIN = 10
COS = math.cos(math.radians(36.5))   # 経度 1 度の長さ（緯度 36.5 度で）

# 本土
MAIN = dict(name='main', lat_top=45.62, lat_bottom=29.95, lon_left=128.4, lon_right=146.0)
MAIN['scale'] = (W - 2 * MARGIN) / ((MAIN['lon_right'] - MAIN['lon_left']) * COS)   # 緯度 1 度あたりのドット
MAIN['x0'], MAIN['y0'] = MARGIN, MARGIN
H = int(round((MAIN['lat_top'] - MAIN['lat_bottom']) * MAIN['scale'])) + 2 * MARGIN


def inset(name, lat_top, lat_bottom, lon_left, lon_right, x0, y0, width):
    cos = math.cos(math.radians((lat_top + lat_bottom) / 2))
    scale = width / ((lon_right - lon_left) * cos)
    return dict(name=name, lat_top=lat_top, lat_bottom=lat_bottom, lon_left=lon_left, lon_right=lon_right,
                x0=x0, y0=y0, scale=scale, cos=cos,
                w=width, h=int(round((lat_top - lat_bottom) * scale)))


# 小笠原 → 右下（太平洋）
OGASA = inset('ogasawara', 27.8, 24.0, 141.0, 142.5, W - 70, H - 200, 52)
MAIN['cos'] = COS
MAIN['w'], MAIN['h'] = W - 2 * MARGIN, H - 2 * MARGIN
REGIONS = [MAIN, OGASA]


def region_of(lat, lon):
    for r in REGIONS:
        if r['lat_bottom'] <= lat <= r['lat_top'] and r['lon_left'] <= lon <= r['lon_right']:
            if r is MAIN and lat < 30.0 and lon < 131.5:
                continue   # トカラ列島より南は枠のほうへ
            return r
    return None


def project(r, lat, lon):
    return (r['x0'] + (lon - r['lon_left']) * r['cos'] * r['scale'],
            r['y0'] + (r['lat_top'] - lat) * r['scale'])


def main():
    count = [[0] * W for _ in range(H)]
    n = miss = 0
    with open(TOWNS, encoding='utf-8') as f:
        for row in csv.DictReader(f):
            lat, lon = float(row['lat']), float(row['lon'])
            r = region_of(lat, lon)
            if r is None:
                miss += 1
                continue
            x, y = project(r, lat, lon)
            xi, yi = int(x), int(y)
            if 0 <= xi < W and 0 <= yi < H:
                count[yi][xi] += 1
                n += 1
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    px = img.load()
    for y in range(H):
        for x in range(W):
            c = count[y][x]
            if c:
                # 点が多い所（都市）ほど明るく
                a = min(255, 70 + 45 * c)
                g = min(255, 150 + 25 * c)
                px[x, y] = (0, g, 65, a)
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT, 13)
    line = (0, 255, 65, 90)
    # 経緯線（5 度ごと、点線）
    for lat in range(30, 46, 5):
        y = project(MAIN, lat, 130)[1]
        for x in range(MARGIN, W - MARGIN, 6):
            d.point((x, y), fill=(0, 255, 65, 45))
        d.text((W - MARGIN - 30, y - 15), f'{lat}N', font=font, fill=(0, 255, 65, 110))
    for lon in range(130, 146, 5):
        x = project(MAIN, 40, lon)[0]
        for y in range(MARGIN, H - MARGIN, 6):
            d.point((x, y), fill=(0, 255, 65, 45))
        d.text((x + 3, H - MARGIN - 16), f'{lon}E', font=font, fill=(0, 255, 65, 110))
    # 枠
    r = OGASA
    box = (r['x0'] - 6, r['y0'] - 6, r['x0'] + r['w'] + 6, r['y0'] + r['h'] + 6)
    d.rectangle(box, outline=line, width=1)
    d.text((box[0] - 40, box[1] - 16), 'OGASAWARA', font=font, fill=(0, 255, 65, 120))
    out = os.path.join(APP, 'res', 'drawable-nodpi')
    os.makedirs(out, exist_ok=True)
    img.save(os.path.join(out, 'japan_dots.png'), optimize=True)

    # Java（同じ式）
    def jregion(r):
        return (f'      new Region({r["lat_top"]}, {r["lat_bottom"]}, {r["lon_left"]}, {r["lon_right"]}, '
                f'{r["x0"]}f, {r["y0"]}f, {r["scale"]:.6f}, {r["cos"]:.8f})')
    java = f'''package jp.hasami.geolog;

/** 自動生成（tools/gen_map.py）。背景の地図 japan_dots.png（{W}x{H}）の上で、緯度経度がどこに当たるか */
final class MapProj {{
  private MapProj() {{}}

  static final int W = {W}, H = {H};

  /** 住所を重ねて出す、日本海の空いた所（絵の上の位置。左, 上, 右, 下） */
  static final float[] TEXT_BOX = {{14f, 14f, 438f, 330f}};

  static final class Region {{
    final double latTop, latBottom, lonLeft, lonRight, scale, cos;
    final float x0, y0;

    Region(double latTop, double latBottom, double lonLeft, double lonRight, float x0, float y0, double scale, double cos) {{
      this.latTop = latTop; this.latBottom = latBottom; this.lonLeft = lonLeft; this.lonRight = lonRight;
      this.x0 = x0; this.y0 = y0; this.scale = scale; this.cos = cos;
    }}

    boolean contains(double lat, double lon) {{
      return lat >= latBottom && lat <= latTop && lon >= lonLeft && lon <= lonRight;
    }}
  }}

  /** 本土・小笠原（南西諸島は描かない。そこにいるときはピンを出さない） */
  static final Region[] REGIONS = {{
{jregion(MAIN)},
{jregion(OGASA)},
  }};

  /** 絵の上の位置 [x, y]。どの枠にも入らなければ null */
  static float[] project(double lat, double lon) {{
    for (int i = 0; i < REGIONS.length; i++) {{
      Region r = REGIONS[i];
      if (!r.contains(lat, lon)) continue;
      if (i == 0 && lat < 30.0 && lon < 131.5) continue;   // トカラ列島より南は枠のほうへ
      return new float[] {{(float) (r.x0 + (lon - r.lonLeft) * r.cos * r.scale), (float) (r.y0 + (r.latTop - lat) * r.scale)}};
    }}
    return null;
  }}
}}
'''
    open(os.path.join(APP, 'java', 'jp', 'hasami', 'geolog', 'MapProj.java'), 'w').write(java)
    print(f'{W}x{H}、点 {n}（枠の外 {miss}）')


if __name__ == '__main__':
    main()
