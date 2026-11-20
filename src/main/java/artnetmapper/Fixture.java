package artnetmapper;

/**
 * One fixture on the canvas: a single light (par), a line of elements (strip)
 * or a grid of elements (matrix). Created by {@link ArtNetMapper#fixture},
 * {@link ArtNetMapper#strip} or {@link ArtNetMapper#matrix}.
 *
 * <p>Placement methods return the fixture so calls can be chained:
 * {@code dmx.strip(px, 9, 30).line(50, 400, 550, 400);}
 */
public class Fixture {

  static final int PAR = 0, STRIP = 1, MATRIX = 2;

  final FixtureType type;
  final int universe;
  /** First DMX channel, 1-based. */
  final int channel;
  final int count;
  final int kind;
  final int cols, rows;

  /** Constant channel values, per type channel. */
  final int[] constants;

  /** Sample points per element, in DMX order. */
  final float[] px, py;
  /** Last sampled (pre-correction) color per element, for the overlay. */
  final int[] sampled;

  boolean whiteMode = true;
  boolean placed;
  boolean warnedUnplaced;
  float gamma = Float.NaN;
  int[] gammaLut;
  int sampleRadius = 0;

  // matrix placement state
  private boolean hasRect;
  private float rx, ry, rw, rh, angle;
  private int wiring = ArtNetMapper.PROGRESSIVE;
  private int corner = ArtNetMapper.TOP_LEFT;
  private int order = ArtNetMapper.ROWS;

  Fixture(FixtureType type, int universe, int channel, int kind, int cols, int rows) {
    this.type = type;
    this.universe = universe;
    this.channel = channel;
    this.kind = kind;
    this.cols = cols;
    this.rows = rows;
    this.count = cols * rows;
    this.constants = type.defaults.clone();
    this.px = new float[count];
    this.py = new float[count];
    this.sampled = new int[count];
  }

  /** Total DMX channels used. */
  public int channelCount() {
    return count * type.size();
  }

  /** Last DMX channel used, 1-based. */
  public int lastChannel() {
    return channel + channelCount() - 1;
  }

  /** Number of light elements. */
  public int size() {
    return count;
  }

  /** Places every element at one point. Typical for a par. */
  public Fixture at(float x, float y) {
    placed = true;
    for (int i = 0; i < count; i++) {
      px[i] = x;
      py[i] = y;
    }
    return this;
  }

  /**
   * Spreads the elements evenly from (x1, y1), the first element,
   * to (x2, y2), the last element. Swap the points to reverse a strip.
   */
  public Fixture line(float x1, float y1, float x2, float y2) {
    placed = true;
    for (int i = 0; i < count; i++) {
      float t = count == 1 ? 0f : i / (float) (count - 1);
      px[i] = x1 + (x2 - x1) * t;
      py[i] = y1 + (y2 - y1) * t;
    }
    return this;
  }

  /** Lays a matrix out as a grid filling this rectangle (element centers). */
  public Fixture rect(float x, float y, float w, float h) {
    if (kind != MATRIX) {
      throw new IllegalStateException("ArtNetMapper: rect() is for matrices; use at() or line() for this fixture");
    }
    hasRect = true;
    placed = true;
    rx = x;
    ry = y;
    rw = w;
    rh = h;
    layoutGrid();
    return this;
  }

  /** Rotates a matrix about the center of its rect, in radians. */
  public Fixture rotate(float radians) {
    if (kind != MATRIX) {
      throw new IllegalStateException("ArtNetMapper: rotate() is for matrices");
    }
    angle = radians;
    layoutGrid();
    return this;
  }

  /**
   * Matrix wiring order.
   *
   * @param wiring {@link ArtNetMapper#PROGRESSIVE} or {@link ArtNetMapper#SERPENTINE}
   * @param corner {@link ArtNetMapper#TOP_LEFT}, {@link ArtNetMapper#TOP_RIGHT},
   *               {@link ArtNetMapper#BOTTOM_LEFT} or {@link ArtNetMapper#BOTTOM_RIGHT}
   * @param order  {@link ArtNetMapper#ROWS} or {@link ArtNetMapper#COLUMNS}
   */
  public Fixture wiring(int wiring, int corner, int order) {
    if (kind != MATRIX) {
      throw new IllegalStateException("ArtNetMapper: wiring() is for matrices");
    }
    if (wiring != ArtNetMapper.PROGRESSIVE && wiring != ArtNetMapper.SERPENTINE) {
      throw new IllegalArgumentException("ArtNetMapper: wiring must be PROGRESSIVE or SERPENTINE");
    }
    if (corner < ArtNetMapper.TOP_LEFT || corner > ArtNetMapper.BOTTOM_RIGHT) {
      throw new IllegalArgumentException("ArtNetMapper: corner must be TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT or BOTTOM_RIGHT");
    }
    if (order != ArtNetMapper.ROWS && order != ArtNetMapper.COLUMNS) {
      throw new IllegalArgumentException("ArtNetMapper: order must be ROWS or COLUMNS");
    }
    this.wiring = wiring;
    this.corner = corner;
    this.order = order;
    layoutGrid();
    return this;
  }

  /** Averages a square of this radius (in sketch pixels) around each sample point. 0 = one pixel. */
  public Fixture sampleRadius(int radius) {
    this.sampleRadius = Math.max(0, radius);
    return this;
  }

  /** Turns automatic white extraction on or off for RGBW fixtures. */
  public Fixture whiteMode(boolean on) {
    this.whiteMode = on;
    return this;
  }

  /** Per-fixture gamma; overrides the global gamma. 1.0 disables correction. */
  public Fixture gamma(float g) {
    if (!(g > 0)) throw new IllegalArgumentException("ArtNetMapper: gamma must be > 0");
    this.gamma = g;
    this.gammaLut = ColorMath.gammaTable(g);
    return this;
  }

  /**
   * Sets a channel to a fixed value 0-255 on every element. If several channels share
   * the name (e.g. "unused"), all of them are set. The sampled w channel can be set
   * once {@link #whiteMode(boolean) whiteMode(false)} is called.
   */
  public Fixture set(String channelName, int value) {
    int i = type.indexOf(channelName);
    if (i < 0) {
      throw new IllegalArgumentException("ArtNetMapper: no channel \"" + channelName + "\" in " + type);
    }
    if (type.defaults[i] < 0 && !(i == type.w && !whiteMode)) {
      String hint = i == type.w ? "call whiteMode(false) first, or "
          : "";
      throw new IllegalArgumentException("ArtNetMapper: channel \"" + channelName
          + "\" is sampled from the canvas; " + hint + "give it a value in the type (e.g. \"" + channelName + "=0\") to set it");
    }
    int v = Math.max(0, Math.min(255, value));
    for (int k = 0; k < type.size(); k++) {
      if (type.names[k].equals(type.names[i])) constants[k] = v;
    }
    return this;
  }

  /** Current value of a constant channel. */
  public int get(String channelName) {
    int i = type.indexOf(channelName);
    if (i < 0) throw new IllegalArgumentException("ArtNetMapper: no channel \"" + channelName + "\" in " + type);
    return constants[i];
  }

  /** Sample point x of element i (DMX order). */
  public float x(int i) {
    return px[i];
  }

  /** Sample point y of element i (DMX order). */
  public float y(int i) {
    return py[i];
  }

  /** Human-readable address, e.g. "u0 ch1-4". */
  public String address() {
    return "u" + universe + " ch" + channel + "-" + lastChannel();
  }

  @Override
  public String toString() {
    String k = kind == PAR ? "fixture" : kind == STRIP ? "strip(" + count + ")" : "matrix(" + cols + "x" + rows + ")";
    return k + " " + address();
  }

  private void layoutGrid() {
    if (!hasRect) return;
    float cx = rx + rw / 2f, cy = ry + rh / 2f;
    float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
    int[] cr = new int[2];
    for (int i = 0; i < count; i++) {
      gridPosition(i, cols, rows, wiring, corner, order, cr);
      float x = rx + (cr[0] + 0.5f) * rw / cols;
      float y = ry + (cr[1] + 0.5f) * rh / rows;
      float dx = x - cx, dy = y - cy;
      px[i] = cx + dx * cos - dy * sin;
      py[i] = cy + dx * sin + dy * cos;
    }
  }

  /**
   * Maps DMX element index i to a grid cell.
   *
   * @param out receives {column, row}, with column 0 at the left and row 0 at the top
   */
  static void gridPosition(int i, int cols, int rows, int wiring, int corner, int order, int[] out) {
    int col, row;
    if (order == ArtNetMapper.ROWS) {
      row = i / cols;
      col = i % cols;
      if (wiring == ArtNetMapper.SERPENTINE && (row & 1) == 1) col = cols - 1 - col;
    } else {
      col = i / rows;
      row = i % rows;
      if (wiring == ArtNetMapper.SERPENTINE && (col & 1) == 1) row = rows - 1 - row;
    }
    if (corner == ArtNetMapper.TOP_RIGHT || corner == ArtNetMapper.BOTTOM_RIGHT) col = cols - 1 - col;
    if (corner == ArtNetMapper.BOTTOM_LEFT || corner == ArtNetMapper.BOTTOM_RIGHT) row = rows - 1 - row;
    out[0] = col;
    out[1] = row;
  }
}
