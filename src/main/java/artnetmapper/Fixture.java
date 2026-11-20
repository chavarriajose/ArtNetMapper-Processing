package artnetmapper;

import java.util.Arrays;

/**
 * A DMX light: a number of channels per pixel, at a DMX address. A par is a fixture
 * with one pixel; a strip is a line of pixels; a matrix is a grid of pixels.
 * Create one with {@link ArtNetMapper#fixture}, {@link ArtNetMapper#strip} or
 * {@link ArtNetMapper#matrix}.
 *
 * <p>Channels inside a fixture count from 0, in the order the light's manual lists
 * them. Every channel is 0 until you set it with {@link #setVal} or link it to the
 * canvas with {@link #colorChannels}.
 *
 * <pre>
 * Fixture par = dmx.fixture(8, 1);      // 8 channels at DMX address 1
 * par.setPos(150, 150);
 * par.colorChannels(1, 2, 3, 4);        // r, g, b, w follow the canvas
 * par.setVal(0, 255);                   // dimmer, sent exactly as written
 * </pre>
 *
 * Every method can be called in setup(), draw() or Processing event handlers
 * (mousePressed, keyPressed...). Calls from your own threads or library callbacks
 * (e.g. oscEvent) may show up half-applied for one frame.
 */
public class Fixture {

  static final int PAR = 0, STRIP = 1, MATRIX = 2;

  final int universe;
  /** First DMX address, 1-based. */
  final int address;
  /** Channels per pixel. */
  final int channels;
  final int count;
  final int kind;
  final int cols, rows;

  /** Hand-set values, per pixel and channel: [pixel * channels + ch]. */
  final int[] values;
  /** True where a hand-set value overrides a canvas-linked channel. */
  final boolean[] manual;
  /** Last output values, same layout as {@link #values}. */
  final int[] output;
  /** Role of each channel: -1 none, 0 r, 1 g, 2 b, 3 w. */
  int[] role;
  int colorCount;

  /** Sample points per pixel, in DMX order. */
  final float[] px, py;
  /** Last color read from the canvas per pixel (before correction), for the overlay. */
  final int[] sampled;

  boolean whiteMode = true;
  /** Own gamma, or NaN to use the global one. */
  float gamma = Float.NaN;
  int[] gammaLut;
  int sampleRadius = 0;
  boolean placed;
  boolean warnedUnplaced;

  /** Set by the mapper when added. */
  ArtNetMapper mapper;
  byte[] universeData;

  private final Pixel[] pixels;

  // matrix grid state; hasRect is false once the fixture is placed another way
  boolean hasRect;
  private float rx, ry, rw, rh, angle;
  private int wiring = ArtNetMapper.PROGRESSIVE;
  private int corner = ArtNetMapper.TOP_LEFT;
  private int order = ArtNetMapper.ROWS;

  Fixture(int channels, int universe, int address, int kind, int cols, int rows) {
    this.channels = channels;
    this.universe = universe;
    this.address = address;
    this.kind = kind;
    this.cols = cols;
    this.rows = rows;
    this.count = cols * rows;
    this.values = new int[count * channels];
    this.manual = new boolean[count * channels];
    this.output = new int[count * channels];
    this.role = new int[channels];
    Arrays.fill(role, -1);
    this.px = new float[count];
    this.py = new float[count];
    this.sampled = new int[count];
    this.pixels = new Pixel[count];
  }

  // ------------------------------------------------------------------ info

  /** Channels per pixel (the number given when it was created). */
  public int channels() {
    return channels;
  }

  /** Number of pixels: 1 for a par. */
  public int pixels() {
    return count;
  }

  /** Total DMX channels used: pixels x channels per pixel. */
  public int totalChannels() {
    return count * channels;
  }

  /** First DMX address, 1-512, as given when it was created. */
  public int address() {
    return address;
  }

  /** Last DMX address used, 1-512. */
  public int lastAddress() {
    return address + totalChannels() - 1;
  }

  /** Art-Net universe, counted from 0. */
  public int universe() {
    return universe;
  }

  private String where() {
    return "universe " + universe + ", DMX " + address + "-" + lastAddress();
  }

  /** e.g. "strip, 30 pixels x 3 channels, universe 1, DMX 9-98". */
  @Override
  public String toString() {
    String what = kind == PAR ? "fixture, " + plural(channels, "channel")
        : kind == STRIP ? "strip, " + plural(count, "pixel") + " x " + plural(channels, "channel")
        : "matrix, " + cols + "x" + rows + " pixels x " + plural(channels, "channel");
    return what + ", " + where();
  }

  private String kindName() {
    return kind == PAR ? "fixture" : kind == STRIP ? "strip" : "matrix";
  }

  private static String plural(int n, String word) {
    return n + " " + word + (n == 1 ? "" : "s");
  }

  /** The placement call that fits this kind of fixture, for messages. */
  String placeHint() {
    return kind == PAR ? "setPos(x, y)" : kind == STRIP ? "line(x1, y1, x2, y2)" : "rect(x, y, w, h)";
  }

  // ------------------------------------------------------------------ placement

  /**
   * Places the fixture (all its pixels) at one point, in sketch pixels (or in the
   * {@link ArtNetMapper#source} buffer's pixels). Typical for a par.
   */
  public Fixture setPos(float x, float y) {
    placed = true;
    hasRect = false;
    for (int i = 0; i < count; i++) {
      px[i] = x;
      py[i] = y;
    }
    return this;
  }

  /**
   * Spreads the pixels evenly from (x1, y1), where pixel 0 sits, to (x2, y2), where
   * the last pixel sits. Swap the points to reverse a strip.
   */
  public Fixture line(float x1, float y1, float x2, float y2) {
    placed = true;
    hasRect = false;
    for (int i = 0; i < count; i++) {
      float t = count == 1 ? 0f : i / (float) (count - 1);
      px[i] = x1 + (x2 - x1) * t;
      py[i] = y1 + (y2 - y1) * t;
    }
    return this;
  }

  /**
   * Lays a matrix out as a grid filling this rectangle, one pixel in the center of
   * each cell. Which cell is pixel 0 depends on {@link #wiring}.
   */
  public Fixture rect(float x, float y, float w, float h) {
    requireMatrix("rect()");
    hasRect = true;
    placed = true;
    rx = x;
    ry = y;
    rw = w;
    rh = h;
    layoutGrid();
    return this;
  }

  /** Rotates a matrix about the center of its rect. Use radians(degrees) to give degrees. */
  public Fixture rotate(float radians) {
    requireMatrix("rotate()");
    angle = radians;
    layoutGrid();
    return this;
  }

  /**
   * The order the matrix's pixels are wired in. Default: PROGRESSIVE, TOP_LEFT, ROWS.
   *
   * @param wiring {@link ArtNetMapper#PROGRESSIVE} or {@link ArtNetMapper#SERPENTINE}
   * @param corner where pixel 0 is: {@link ArtNetMapper#TOP_LEFT}, {@link ArtNetMapper#TOP_RIGHT},
   *               {@link ArtNetMapper#BOTTOM_LEFT} or {@link ArtNetMapper#BOTTOM_RIGHT}
   * @param order  {@link ArtNetMapper#ROWS} or {@link ArtNetMapper#COLUMNS}
   */
  public Fixture wiring(int wiring, int corner, int order) {
    requireMatrix("wiring()");
    String example = " Example: wiring(ArtNetMapper.SERPENTINE, ArtNetMapper.TOP_LEFT, ArtNetMapper.ROWS)";
    if (wiring != ArtNetMapper.PROGRESSIVE && wiring != ArtNetMapper.SERPENTINE) {
      throw new IllegalArgumentException("ArtNetMapper: wiring(wiring, corner, order): wiring must be"
          + " ArtNetMapper.PROGRESSIVE or ArtNetMapper.SERPENTINE, got " + wiring + "." + example);
    }
    if (corner < ArtNetMapper.TOP_LEFT || corner > ArtNetMapper.BOTTOM_RIGHT) {
      throw new IllegalArgumentException("ArtNetMapper: wiring(wiring, corner, order): corner must be"
          + " ArtNetMapper.TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT or BOTTOM_RIGHT, got " + corner + "." + example);
    }
    if (order != ArtNetMapper.ROWS && order != ArtNetMapper.COLUMNS) {
      throw new IllegalArgumentException("ArtNetMapper: wiring(wiring, corner, order): order must be"
          + " ArtNetMapper.ROWS or ArtNetMapper.COLUMNS, got " + order + "." + example);
    }
    this.wiring = wiring;
    this.corner = corner;
    this.order = order;
    layoutGrid();
    return this;
  }

  private void requireMatrix(String what) {
    if (kind != MATRIX) {
      throw new IllegalStateException("ArtNetMapper: " + what + " is only for matrices. Place this "
          + kindName() + " with " + placeHint() + ".");
    }
  }

  /** x of the position set with setPos() (pixel 0). For one pixel of a strip: pixel(i).x(). */
  public float x() {
    return px[0];
  }

  /** y of the position set with setPos() (pixel 0). For one pixel of a strip: pixel(i).y(). */
  public float y() {
    return py[0];
  }

  // ------------------------------------------------------------------ canvas link

  /**
   * Links channels to the canvas color at the fixture's position. Give 3 channels
   * (red, green, blue) or 4 (red, green, blue, white), in that order, counted from 0.
   * These channels then follow the canvas, even if they were set by hand before.
   * {@code colorChannels()} with nothing in the brackets removes the link.
   *
   * <pre>
   * par.colorChannels(0, 1, 2, 3);   // 4-channel mode: r g b w
   * par.colorChannels(1, 2, 3, 4);   // 8-channel mode: dimmer r g b w strobe mode speed
   * </pre>
   */
  public Fixture colorChannels(int... rgbw) {
    if (rgbw.length != 0 && rgbw.length != 3 && rgbw.length != 4) {
      throw new IllegalArgumentException("ArtNetMapper: colorChannels() needs 3 channels (red, green, blue)"
          + " or 4 (red, green, blue, white), got " + rgbw.length + ". Example: colorChannels(0, 1, 2).");
    }
    int[] r = new int[channels];
    Arrays.fill(r, -1);
    for (int i = 0; i < rgbw.length; i++) {
      checkChannel(rgbw[i]);
      if (r[rgbw[i]] >= 0) {
        throw new IllegalArgumentException("ArtNetMapper: colorChannels(): channel " + rgbw[i] + " is given twice.");
      }
      r[rgbw[i]] = i;
    }
    role = r;
    colorCount = rgbw.length;
    for (int e = 0; e < count; e++) for (int ch : rgbw) manual[e * channels + ch] = false;
    if (mapper != null) mapper.linkChanged();
    return this;
  }

  /**
   * Averages a square around each position instead of reading one pixel.
   *
   * @param radius in sketch pixels (scales with pixelDensity); 0 = one pixel (default)
   */
  public Fixture sampleRadius(int radius) {
    this.sampleRadius = Math.max(0, radius);
    return this;
  }

  /**
   * White extraction, for 4 color channels (default on): white = the smallest of red,
   * green and blue, which is then taken away from all three. Off: white stays 0.
   */
  public Fixture whiteMode(boolean on) {
    this.whiteMode = on;
    return this;
  }

  /**
   * Gamma for this fixture's canvas colors, instead of the global one.
   *
   * @param gamma more than 0; 1.0 = no correction
   */
  public Fixture gamma(float gamma) {
    this.gammaLut = ColorMath.gammaTable(ArtNetMapper.checkGamma(gamma));
    this.gamma = gamma;
    return this;
  }

  /** This fixture's gamma, or the global one if it has none of its own. */
  public float getGamma() {
    return Float.isNaN(gamma) && mapper != null ? mapper.getGamma() : gamma;
  }

  /** The sample radius set with sampleRadius() (0 = one pixel). */
  public int getSampleRadius() {
    return sampleRadius;
  }

  /** Whether white extraction is on (see whiteMode()). */
  public boolean getWhiteMode() {
    return whiteMode;
  }

  // ------------------------------------------------------------------ manual values

  /**
   * Sets one channel on every pixel. The value is sent exactly as written: no gamma,
   * no brightness. On a canvas-linked channel it wins until {@link #release()}.
   *
   * @param ch    channel inside the fixture, counted from 0
   * @param value 0-255; clamped; decimals are rounded, so red(c) works directly
   */
  public Fixture setVal(int ch, float value) {
    checkChannel(ch);
    for (int e = 0; e < count; e++) put(e, ch, value);
    return this;
  }

  /**
   * Sets channels 0, 1, 2... on every pixel, e.g. {@code setVals(255, 0, 0, 0)}.
   * Covers canvas-linked channels too: this is how a color is hard-coded. To set
   * only one channel, use {@link #setVal}.
   *
   * @param values 0-255 each, at most one per channel
   */
  public Fixture setVals(float... values) {
    checkVals(values);
    for (int e = 0; e < count; e++) for (int k = 0; k < values.length; k++) put(e, k, values[k]);
    return this;
  }

  /** Stops hand-set values overriding the canvas, on every pixel. Unlinked channels keep their value. */
  public Fixture release() {
    Arrays.fill(manual, false);
    return this;
  }

  /** Stops the hand-set value of one channel overriding the canvas, on every pixel. */
  public Fixture release(int ch) {
    checkChannel(ch);
    for (int e = 0; e < count; e++) manual[e * channels + ch] = false;
    return this;
  }

  /**
   * Value of a channel on pixel 0: the hand-set value, or for a canvas-linked channel
   * the value computed at the end of the last draw().
   */
  public int getVal(int ch) {
    checkChannel(ch);
    return current(0, ch);
  }

  int current(int e, int ch) {
    int i = e * channels + ch;
    return role[ch] >= 0 && !manual[i] ? output[i] : values[i];
  }

  /** One pixel of a strip or matrix, counted from 0 in DMX order, to set it on its own. */
  public Pixel pixel(int index) {
    checkPixel(index);
    Pixel p = pixels[index];
    if (p == null) p = pixels[index] = new Pixel(index);
    return p;
  }

  /** One pixel of a strip or matrix. Get it with {@link Fixture#pixel}. */
  public class Pixel {
    private final int e;

    Pixel(int e) {
      this.e = e;
    }

    /** Sets one channel of this pixel (counted from 0) to 0-255, sent exactly as written. */
    public Pixel setVal(int ch, float value) {
      checkChannel(ch);
      put(e, ch, value);
      return this;
    }

    /** Sets channels 0, 1, 2... of this pixel, e.g. {@code setVals(255, 0, 0)}. */
    public Pixel setVals(float... values) {
      checkVals(values);
      for (int k = 0; k < values.length; k++) put(e, k, values[k]);
      return this;
    }

    /** Stops this pixel's hand-set values overriding the canvas. */
    public Pixel release() {
      for (int k = 0; k < channels; k++) manual[e * channels + k] = false;
      return this;
    }

    /** Stops the hand-set value of one channel of this pixel overriding the canvas. */
    public Pixel release(int ch) {
      checkChannel(ch);
      manual[e * channels + ch] = false;
      return this;
    }

    /** Value of a channel of this pixel (hand-set, or computed in the last draw()). */
    public int getVal(int ch) {
      checkChannel(ch);
      return current(e, ch);
    }

    /** x of this pixel's position. */
    public float x() {
      return px[e];
    }

    /** y of this pixel's position. */
    public float y() {
      return py[e];
    }
  }

  private void put(int e, int ch, float value) {
    int i = e * channels + ch;
    values[i] = Math.round(Math.max(0f, Math.min(255f, value)));
    manual[i] = true;
  }

  private void checkChannel(int ch) {
    if (ch < 0 || ch >= channels) {
      throw new IllegalArgumentException("ArtNetMapper: channel " + ch + " doesn't exist. This " + kindName()
          + " has " + plural(channels, "channel") + (kind == PAR ? "" : " per pixel") + ", numbered 0-" + (channels - 1)
          + ". Channels inside a fixture count from 0; the DMX address (" + address + ") is set when it's created.");
    }
  }

  private void checkVals(float[] vals) {
    if (vals.length > channels) {
      throw new IllegalArgumentException("ArtNetMapper: setVals() got " + vals.length + " values, but this "
          + kindName() + " has " + plural(channels, "channel") + (kind == PAR ? "" : " per pixel")
          + " (0-" + (channels - 1) + "). Pass at most " + channels + ".");
    }
  }

  private void checkPixel(int index) {
    if (index < 0 || index >= count) {
      throw new IllegalArgumentException("ArtNetMapper: pixel " + index + " doesn't exist. This " + kindName()
          + " has " + plural(count, "pixel") + ", numbered 0-" + (count - 1) + ".");
    }
  }

  // ------------------------------------------------------------------ geometry

  /** Smallest distance between consecutive pixels (DMX order); infinite for one pixel. */
  float minSpacing() {
    float min = Float.POSITIVE_INFINITY;
    for (int i = 1; i < count; i++) {
      float dx = px[i] - px[i - 1], dy = py[i] - py[i - 1];
      min = Math.min(min, (float) Math.sqrt(dx * dx + dy * dy));
    }
    return min;
  }

  /** Corners of the matrix rect grown by {@code margin}, rotated: x0,y0 .. x3,y3. */
  float[] outline(float margin) {
    float cx = rx + rw / 2f, cy = ry + rh / 2f;
    float hw = rw / 2f + margin, hh = rh / 2f + margin;
    float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
    float[] sx = {-hw, hw, hw, -hw}, sy = {-hh, -hh, hh, hh};
    float[] out = new float[8];
    for (int k = 0; k < 4; k++) {
      out[2 * k] = cx + sx[k] * cos - sy[k] * sin;
      out[2 * k + 1] = cy + sx[k] * sin + sy[k] * cos;
    }
    return out;
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
   * Maps DMX pixel index i to a grid cell.
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
