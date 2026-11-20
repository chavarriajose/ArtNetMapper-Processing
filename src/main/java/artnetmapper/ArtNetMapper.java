package artnetmapper;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import processing.core.PApplet;
import processing.core.PGraphics;

/**
 * Maps points of a Processing sketch to DMX fixtures and sends them over Art-Net.
 * One ArtNetMapper sends to one Art-Net node; create several for several nodes.
 *
 * <pre>
 * ArtNetMapper dmx;
 *
 * void setup() {
 *   size(600, 400);
 *   String[] nodes = ArtNetMapper.list();          // prints the nodes found
 *   dmx = new ArtNetMapper(this, nodes[0]);         // no node? use "127.0.0.1"
 *   Fixture par = dmx.fixture(4, 1);                // 4 channels at DMX address 1
 *   par.setPos(300, 200);
 *   par.colorChannels(0, 1, 2, 3);                  // r, g, b, w follow the canvas
 * }
 *
 * void draw() {
 *   background(frameCount % 255, 0, 120);          // the light follows the canvas
 * }
 * </pre>
 */
public class ArtNetMapper {

  /** Library version, shown in the console on startup. */
  public static final String VERSION = "0.2.0";

  /** {@link Fixture#wiring}: every row (or column) runs in the same direction. */
  public static final int PROGRESSIVE = 0;
  /** {@link Fixture#wiring}: every other row (or column) runs back the other way. */
  public static final int SERPENTINE = 1;
  /** {@link Fixture#wiring}: the corner where pixel 0 sits. */
  public static final int TOP_LEFT = 10, TOP_RIGHT = 11, BOTTOM_LEFT = 12, BOTTOM_RIGHT = 13;
  /** {@link Fixture#wiring}: pixel 1 is next to pixel 0 along a row, or along a column. */
  public static final int ROWS = 20, COLUMNS = 21;

  static final int DEFAULT_SCAN_MS = 1500;

  private final PApplet parent;
  private final String ip;
  private final Sender sender;
  private final List<Fixture> fixtures = new ArrayList<>();

  private PGraphics source;
  private float gamma = 2.5f;
  private int[] gammaLut = ColorMath.gammaTable(2.5f);
  private float brightness = 1f;
  private boolean autoSend = true;
  private boolean showLocations = true;
  private volatile boolean blackoutOnExit = true;
  /** True when at least one fixture follows the canvas, so the canvas must be read. */
  private boolean anyLinked;

  private final Thread shutdownHook;
  private final int[] rgbw = new int[4];
  /** Prints a line per added fixture (off in unit tests). */
  private final boolean verbose;

  /**
   * Connects to an Art-Net node.
   *
   * @param parent the sketch: {@code this}
   * @param ip     the node's IP, e.g. {@code nodes[0]} from {@link #list()}, or typed like "2.0.0.1".
   *               "127.0.0.1" sends to this computer, to work without a node.
   */
  public ArtNetMapper(PApplet parent, String ip) {
    this(parent, ip, true);
  }

  ArtNetMapper(PApplet parent, String ip, boolean network) {
    this.parent = parent;
    this.verbose = network;
    this.ip = ip == null ? null : ip.trim();
    InetAddress address = Discovery.parse(this.ip);
    if (address == null) {
      throw new IllegalArgumentException(ip == null
          ? "ArtNetMapper: no IP given (null). Pass an IP from ArtNetMapper.list(), or type one like \"2.0.0.1\"."
          : "ArtNetMapper: \"" + ip + "\" is not an IP address. Pass an IP from ArtNetMapper.list(), or type one like \"2.0.0.1\".");
    }
    if (network) {
      System.out.println("ArtNetMapper: version " + VERSION);
      sender = new Sender(this.ip, address);
      shutdownHook = new Thread(this::dispose, "ArtNetMapper-exit");
      Runtime.getRuntime().addShutdownHook(shutdownHook);
    } else {
      sender = new Sender(this.ip, address, null);
      shutdownHook = null;
    }
    // Registered last, so Processing never calls into a half-built object.
    if (parent != null) {
      parent.registerMethod("draw", this);
      parent.registerMethod("dispose", this);
    }
  }

  // ------------------------------------------------------------------ help

  /**
   * Prints the public methods of a class to the console, grouped by what they do,
   * with the type and name of every parameter.
   *
   * <pre>
   * ArtNetMapper.printPublicMethodsFor(ArtNetMapper.class);
   * ArtNetMapper.printPublicMethodsFor(Fixture.class);
   * ArtNetMapper.printPublicMethodsFor(Fixture.Pixel.class);
   * </pre>
   */
  public static void printPublicMethodsFor(Class<?> c) {
    for (String line : MethodList.lines(c)) System.out.println(line);
  }

  // ------------------------------------------------------------------ discovery

  /** Scans for Art-Net nodes (about 1.5 s), prints a numbered list and returns their IPs. */
  public static String[] list() {
    return list(DEFAULT_SCAN_MS);
  }

  /** Like {@link #list()}, waiting {@code timeoutMs} milliseconds for replies. */
  public static String[] list(int timeoutMs) {
    List<Discovery.Node> nodes = Discovery.scan(timeoutMs);
    String[] ips = new String[nodes.size()];
    if (nodes.isEmpty()) {
      System.out.println("ArtNetMapper: no Art-Net nodes found. Check the cable and that the node is powered."
          + " nodes[0] will now fail; to run without a node, use new ArtNetMapper(this, \"127.0.0.1\").");
    } else {
      System.out.println("ArtNetMapper: found " + nodes.size() + " Art-Net node" + (nodes.size() == 1 ? "" : "s") + ":");
      for (int i = 0; i < nodes.size(); i++) {
        Discovery.Node n = nodes.get(i);
        ips[i] = n.ip;
        System.out.println("  [" + i + "] \"" + n.ip + "\"  " + n.label());
      }
    }
    return ips;
  }

  /**
   * True while packets are being sent: the node's network is connected, or the IP is
   * routed. It doesn't prove that a node is listening.
   */
  public boolean isConnected() {
    return sender.isSending();
  }

  /** The IP this ArtNetMapper sends to. */
  public String ip() {
    return ip;
  }

  // ------------------------------------------------------------------ fixtures

  /**
   * Adds a single light (a par: a fixture with one pixel) on universe 0.
   * Place it with {@link Fixture#setPos}.
   *
   * @param channels how many DMX channels the light uses in its current mode (1 or more)
   * @param address  its DMX address, 1-512, as set on the light
   */
  public Fixture fixture(int channels, int address) {
    return fixture(channels, 0, address);
  }

  /**
   * Adds a single light on a universe.
   *
   * @param channels how many DMX channels the light uses (1 or more)
   * @param universe Art-Net universe, counted from 0
   * @param address  DMX address, 1-512, as set on the light
   */
  public Fixture fixture(int channels, int universe, int address) {
    precheck("fixture", channels, universe, address, 1);
    return add(new Fixture(channels, universe, address, Fixture.PAR, 1, 1));
  }

  /**
   * Adds a strip (light bar) of pixels on universe 0. Place it with {@link Fixture#line}.
   *
   * @param channelsPerPixel DMX channels per pixel, e.g. 3 for RGB
   * @param address          DMX address of the first pixel, 1-512
   * @param pixels           number of pixels
   */
  public Fixture strip(int channelsPerPixel, int address, int pixels) {
    return strip(channelsPerPixel, 0, address, pixels);
  }

  /**
   * Adds a strip (light bar) of pixels. Place it with {@link Fixture#line}.
   *
   * @param channelsPerPixel DMX channels per pixel, e.g. 3 for RGB
   * @param universe         Art-Net universe, counted from 0
   * @param address          DMX address of the first pixel, 1-512
   * @param pixels           number of pixels
   */
  public Fixture strip(int channelsPerPixel, int universe, int address, int pixels) {
    if (pixels < 1) throw new IllegalArgumentException("ArtNetMapper: a strip needs at least 1 pixel, got " + pixels);
    precheck("strip", channelsPerPixel, universe, address, pixels);
    Fixture f = add(new Fixture(channelsPerPixel, universe, address, Fixture.STRIP, pixels, 1));
    if (channelsPerPixel > pixels) {
      warn(f + " has more channels per pixel than pixels. Did you mean strip(" + pixels + ", "
          + universe + ", " + address + ", " + channelsPerPixel + ")? Order: channelsPerPixel, universe, address, pixels.");
    }
    return f;
  }

  /**
   * Adds a matrix (grid) of pixels on universe 0. Place it with {@link Fixture#rect}.
   *
   * @param channelsPerPixel DMX channels per pixel, e.g. 3 for RGB
   * @param address          DMX address of pixel 0, 1-512
   * @param cols             pixels per row
   * @param rows             number of rows
   */
  public Fixture matrix(int channelsPerPixel, int address, int cols, int rows) {
    return matrix(channelsPerPixel, 0, address, cols, rows);
  }

  /**
   * Adds a matrix (grid) of pixels. Place it with {@link Fixture#rect}; set the
   * pixel order with {@link Fixture#wiring}.
   *
   * @param channelsPerPixel DMX channels per pixel, e.g. 3 for RGB
   * @param universe         Art-Net universe, counted from 0
   * @param address          DMX address of pixel 0, 1-512
   * @param cols             pixels per row
   * @param rows             number of rows
   */
  public Fixture matrix(int channelsPerPixel, int universe, int address, int cols, int rows) {
    if (cols < 1 || rows < 1) {
      throw new IllegalArgumentException("ArtNetMapper: a matrix needs at least 1x1 pixels, got " + cols + "x" + rows);
    }
    precheck("matrix", channelsPerPixel, universe, address, (long) cols * rows);
    Fixture f = add(new Fixture(channelsPerPixel, universe, address, Fixture.MATRIX, cols, rows));
    if (channelsPerPixel > (long) cols * rows) {
      warn(f + " has more channels per pixel than pixels. Order: channelsPerPixel, universe, address, cols, rows.");
    }
    return f;
  }

  /** Validates before any array is allocated, so huge sizes give a clear message. */
  private static void precheck(String what, int channels, int universe, int address, long pixels) {
    if (channels < 1) {
      throw new IllegalArgumentException("ArtNetMapper: a " + what + " needs at least 1 channel"
          + (what.equals("fixture") ? "" : " per pixel") + ", got " + channels);
    }
    if (universe < 0 || universe > 0x7FFF) {
      throw new IllegalArgumentException("ArtNetMapper: universe must be 0-32767 (counted from 0), got " + universe);
    }
    if (address < 1 || address > 512) {
      throw new IllegalArgumentException("ArtNetMapper: DMX address must be 1-512, as set on the light, got " + address
          + (address == 0 ? ". DMX addresses start at 1." : "."));
    }
    long needed = pixels * channels;
    if (needed > 512) {
      throw new IllegalArgumentException("ArtNetMapper: this " + what + " needs " + needed
          + " channels, but one universe holds 512. Split it into smaller fixtures on separate universes.");
    }
    if (address - 1 + needed > 512) {
      throw new IllegalArgumentException("ArtNetMapper: " + what + " at DMX " + address + " needs " + needed
          + " channels, but a universe ends at 512. Use DMX address " + (513 - needed)
          + " or lower, or put it on another universe.");
    }
  }

  private Fixture add(Fixture f) {
    f.universeData = sender.universe(f.universe).data;
    if (verbose) System.out.println("ArtNetMapper: added " + f);
    for (Fixture o : fixtures) {
      if (o.universe == f.universe && f.address <= o.lastAddress() && o.address <= f.lastAddress()) {
        warn(f + " uses the same DMX channels as " + o + ". Both write to them; the one added last wins.");
      }
    }
    fixtures.add(f);
    f.mapper = this;
    return f;
  }

  /** Called by a fixture when its canvas link changes. */
  void linkChanged() {
    boolean any = false;
    for (Fixture f : fixtures) any |= f.colorCount > 0;
    anyLinked = any;
  }

  /** All fixtures added so far, in the order they were added. */
  public List<Fixture> fixtures() {
    return new ArrayList<>(fixtures);
  }

  // ------------------------------------------------------------------ settings

  /**
   * Reads canvas colors from this buffer instead of the sketch window.
   * Fixture positions are then in the buffer's pixels. {@code null} = the window.
   */
  public ArtNetMapper source(PGraphics pg) {
    this.source = pg;
    return this;
  }

  /**
   * Gamma for canvas colors, for every fixture without its own {@link Fixture#gamma}.
   *
   * @param gamma more than 0; 2.5 (default) matches Resolume, 1.0 = no correction
   */
  public ArtNetMapper gamma(float gamma) {
    this.gamma = checkGamma(gamma);
    this.gammaLut = ColorMath.gammaTable(gamma);
    return this;
  }

  static float checkGamma(float gamma) {
    if (!(gamma > 0)) {
      throw new IllegalArgumentException("ArtNetMapper: gamma must be more than 0 (1.0 = no correction, 2.5 = default), got " + gamma);
    }
    return gamma;
  }

  /** The global gamma set with gamma() (default 2.5). */
  public float getGamma() {
    return gamma;
  }

  /** The brightness set with brightness() (default 1.0). */
  public float getBrightness() {
    return brightness;
  }

  /** The cap set with maxRate() (default 44). */
  public float getMaxRate() {
    return sender.getMaxRate();
  }

  /**
   * Brightness of canvas colors, applied after gamma. Values set with setVal() are not scaled.
   *
   * @param level 0.0 (dark) to 1.0 (full, default); clamped
   */
  public ArtNetMapper brightness(float level) {
    brightness = Math.max(0f, Math.min(1f, level));
    return this;
  }

  /**
   * Caps how many frames per second are sent. DMX itself tops out around 44.
   *
   * @param fps more than 0; default 44
   */
  public ArtNetMapper maxRate(float fps) {
    if (!(fps > 0)) throw new IllegalArgumentException("ArtNetMapper: maxRate must be more than 0 frames per second (default 44), got " + fps);
    sender.maxRate(fps);
    return this;
  }

  /** true (default): read the canvas and send after every draw(). false: call {@link #send()} yourself. */
  public ArtNetMapper autoSend(boolean on) {
    autoSend = on;
    return this;
  }

  /** Draws a marker at each fixture's position, in the color it reads (default on). */
  public ArtNetMapper showLocations(boolean on) {
    showLocations = on;
    return this;
  }

  /** Sends all channels at 0 when the sketch stops (default on). */
  public ArtNetMapper blackoutOnExit(boolean on) {
    blackoutOnExit = on;
    return this;
  }

  /**
   * The current 512 values of a universe, as computed from the fixtures (also during a
   * blackout). <b>Index 0 is DMX address 1</b>, unlike the channels inside a fixture,
   * which count from 0 at the fixture's address.
   */
  public int[] getUniverse(int universe) {
    int[] out = new int[512];
    synchronized (sender.lock) {
      for (Sender.Universe u : sender.universes()) {
        if (u.id == universe) {
          for (int i = 0; i < 512; i++) out[i] = u.data[i] & 0xFF;
        }
      }
    }
    return out;
  }

  // ------------------------------------------------------------------ frame loop

  /** Called by Processing after the sketch's draw(). Don't call it yourself. */
  public void draw() {
    if (autoSend) update();
    if (showLocations && parent != null) Overlay.draw(parent.g, fixtures);
  }

  /** Reads the canvas now and queues the frame for sending. Only needed with autoSend(false). */
  public void send() {
    update();
  }

  private void update() {
    if (!anyLinked) {                 // all fixtures manual: no need to read the canvas
      sample(null, 0, 0, 1);
      return;
    }
    PGraphics src = source != null ? source : (parent != null ? parent.g : null);
    if (src == null) return;
    src.loadPixels();
    sample(src.pixels, src.pixelWidth, src.pixelHeight, src.pixelDensity);
  }

  /**
   * Computes every fixture's output and writes it into the universe buffers.
   * Canvas-linked channels get white extraction, gamma and brightness; hand-set
   * values are written exactly as given.
   */
  void sample(int[] pixels, int pw, int ph, int density) {
    if (sender.isClosed()) return;
    boolean changed = false;
    synchronized (sender.lock) {
      for (Fixture f : fixtures) {
        byte[] buf = f.universeData;
        int size = f.channels;
        int[] role = f.role;
        boolean linked = f.colorCount > 0 && pixels != null;
        int[] lut = f.gammaLut != null ? f.gammaLut : gammaLut;
        boolean white = f.colorCount == 4 && f.whiteMode;
        if (linked && !f.placed && !f.warnedUnplaced) {
          f.warnedUnplaced = true;
          warn(f + " follows the canvas but has no position. Call " + f.placeHint()
              + ". Until then it reads the pixel at (0, 0).");
        }
        for (int e = 0; e < f.count; e++) {
          if (linked) {
            int c = samplePoint(pixels, pw, ph, density, f.px[e], f.py[e], f.sampleRadius);
            f.sampled[e] = c;
            ColorMath.process((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, white, lut, brightness, rgbw);
          }
          int base = e * size;
          int dmx = f.address - 1 + base;
          for (int k = 0; k < size; k++) {
            int i = base + k;
            int v = linked && role[k] >= 0 && !f.manual[i] ? rgbw[role[k]] : f.values[i];
            f.output[i] = v;
            if (buf[dmx + k] != (byte) v) {
              buf[dmx + k] = (byte) v;
              changed = true;
            }
          }
        }
      }
    }
    if (changed) sender.changed();
  }

  /** Average color (0xRRGGBB) of a square around (x, y) in sketch coordinates. Outside = black. */
  static int samplePoint(int[] pixels, int pw, int ph, int density, float x, float y, int radius) {
    int cx = (int) Math.floor(x * density), cy = (int) Math.floor(y * density); // pixel containing the point
    int r = radius * density;
    // Points up to one pixel past the edge (e.g. line(0, y, width, y)) read the edge pixel;
    // points further outside read black.
    if (cx < -1 || cy < -1 || cx > pw || cy > ph) return 0;
    long sr = 0, sg = 0, sb = 0;
    int n = 0;
    for (int yy = cy - r; yy <= cy + r; yy++) {
      int sy = Math.max(0, Math.min(ph - 1, yy));
      for (int xx = cx - r; xx <= cx + r; xx++) {
        int sx = Math.max(0, Math.min(pw - 1, xx));
        int c = pixels[sy * pw + sx];
        sr += (c >> 16) & 0xFF;
        sg += (c >> 8) & 0xFF;
        sb += c & 0xFF;
        n++;
      }
    }
    return (int) ((sr / n) << 16 | (sg / n) << 8 | (sb / n));
  }

  static void warn(String message) {
    System.out.println("ArtNetMapper: warning: " + message);
  }

  // ------------------------------------------------------------------ output

  /**
   * Blackout switch. {@code true}: every light gets 0, whatever the sketch does, until
   * {@code blackout(false)}. Fixture values keep updating in the meantime, so switching
   * it off shows the current state at once.
   */
  public ArtNetMapper blackout(boolean on) {
    sender.blackout(on);
    return this;
  }

  /** Whether the blackout switch is on. */
  public boolean isBlackout() {
    return sender.isBlackout();
  }

  /** Called by Processing when the sketch stops. Don't call it yourself. */
  public void dispose() {
    if (sender.isClosed()) return;
    sender.close(blackoutOnExit);
    if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException e) {
        // the JVM is already shutting down
      }
    }
  }

  /** For tests: the sender, to drive it with a fake clock. */
  Sender sender() {
    return sender;
  }
}
