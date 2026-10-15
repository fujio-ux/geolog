package jp.hasami.geolog;

/** 自動生成（tools/gen_map.py）。背景の地図 japan_dots.png（720x795）の上で、緯度経度がどこに当たるか */
final class MapProj {
  private MapProj() {}

  static final int W = 720, H = 795;

  /** 住所を重ねて出す、日本海の空いた所（絵の上の位置。左, 上, 右, 下） */
  static final float[] TEXT_BOX = {14f, 14f, 438f, 330f};

  static final class Region {
    final double latTop, latBottom, lonLeft, lonRight, scale, cos;
    final float x0, y0;

    Region(double latTop, double latBottom, double lonLeft, double lonRight, float x0, float y0, double scale, double cos) {
      this.latTop = latTop; this.latBottom = latBottom; this.lonLeft = lonLeft; this.lonRight = lonRight;
      this.x0 = x0; this.y0 = y0; this.scale = scale; this.cos = cos;
    }

    boolean contains(double lat, double lon) {
      return lat >= latBottom && lat <= latTop && lon >= lonLeft && lon <= lonRight;
    }
  }

  /** 本土・小笠原（南西諸島は描かない。そこにいるときはピンを出さない） */
  static final Region[] REGIONS = {
      new Region(45.62, 29.95, 128.4, 146.0, 10f, 10f, 49.477375, 0.80385686),
      new Region(27.8, 24.0, 141.0, 142.5, 650f, 595f, 38.537454, 0.89955778),
  };

  /** 絵の上の位置 [x, y]。どの枠にも入らなければ null */
  static float[] project(double lat, double lon) {
    for (int i = 0; i < REGIONS.length; i++) {
      Region r = REGIONS[i];
      if (!r.contains(lat, lon)) continue;
      if (i == 0 && lat < 30.0 && lon < 131.5) continue;   // トカラ列島より南は枠のほうへ
      return new float[] {(float) (r.x0 + (lon - r.lonLeft) * r.cos * r.scale), (float) (r.y0 + (r.latTop - lat) * r.scale)};
    }
    return null;
  }
}
