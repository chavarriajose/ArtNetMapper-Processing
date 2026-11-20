package artnetmapper;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import processing.core.PApplet;
import processing.core.PGraphics;

/**
 * Maps points of a Processing sketch to DMX fixtures and sends them over Art-Net.
 *
 * <pre>
 * ArtNetMapper dmx;
 *
 * void setup() {
 *   size(600, 400);
 *   String[] nodes = ArtNetMapper.list();                 // prints nodes found
 *   dmx = new ArtNetMapper(this, nodes[0]);               // or "2.0.0.1"
 *   FixtureType par = dmx.type("r, g, b, w");
 *   dmx.fixture(par, 1).at(200, 200);
 *   dmx.fixture(par, 5).at(400, 200);
 * }
 *
 * void draw() {
 *   background(frameCount % 255, 0, 120);                 // lights follow the canvas
 * }
 * </pre>
 *
 * One ArtNetMapper sends to one node. Create several for several nodes.
 */
public class ArtNetMapper {

  /** Library version, shown in the console on startup. */
  public static final String VERSION = "0.1.0";

  /** Matrix wiring: every row (or column) runs in the same direction. */
  public static final int PROGRESSIVE = 0;
  /** Matrix wiring: every other row (or column) runs in the opposite direction. */
  public static final int SERPENTINE = 1;
  /** Matrix start corner. */
  public static final int TOP_LEFT = 0, TOP_RIGHT = 1, BOTTOM_LEFT = 2, BOTTOM_RIGHT = 3;
  /** Matrix wiring order: element 2 is next to element 1 along a row / along a column. */
  public static final int ROWS = 0, COLUMNS = 1;

  static final int DEFAULT_SCAN_MS = 1500;

  private final PApplet parent;
  private final String target;

  private final List<Fixture> fixtures = new ArrayList<>();
  /** Universe -> 512 channel values. Guarded by itself. */
  private final Map<Integer, byte[]> frames = new TreeMap<>();
  private final Map<Integer, Integer> sequence = new TreeMap<>();

  private PGraphics source;
  private float gamma = 2.5f;
  private int[] gammaLut = ColorMath.gammaTable(2.5f);
  private float brightness = 1f;
  private volatile float maxRate = 44f;
  private boolean autoSend = true;
  private boolean showLocations = true;
  private boolean blackoutOnExit = true;

  private DatagramSocket socket;
  private volatile InetAddress destination;
  private volatile String connectedIp;
  private volatile boolean pending;
  private volatile long lastSendNanos;
  private volatile boolean disposed;
  private final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
  /** Serialises sequence numbering and socket sends so packets leave in order. */
  private final Object sendLock = new Object();
  /** Connection status, for messages only: 0 unknown, 1 sending on a local link, 2 sending routed, 3 no output. */
  private volatile int status = 0;
  private final ScheduledExecutorService exec;
  private final Thread shutdownHook;
  private final int[] rgbw = new int[4];

  /**
   * @param parent the sketch ({@code this})
   * @param target the node's IP, e.g. {@code nodes[0]} from {@link #list()} or "2.0.0.1"
   */
  public ArtNetMapper(PApplet parent, String target) {
    this(parent, target, true);
  }

  ArtNetMapper(PApplet parent, String target, boolean network) {
    this.parent = parent;
    this.target = target == null ? null : target.trim();
    if (Discovery.parse(this.target) == null) {
      throw new IllegalArgumentException("ArtNetMapper: \"" + target + "\" is not an IPv4 address."
          + " Pass an IP from ArtNetMapper.list(), or type one like \"2.0.0.1\"");
    }
    System.out.println("ArtNetMapper " + VERSION);

    if (parent != null) {
      parent.registerMethod("draw", this);
      parent.registerMethod("dispose", this);
    }

    if (network) {
      try {
        socket = new DatagramSocket(); // ephemeral port: never competes for 6454
        socket.setBroadcast(true);
      } catch (SocketException e) {
        throw new RuntimeException("ArtNetMapper: could not open a UDP socket: " + e.getMessage(), e);
      }
      connect(true);
      exec = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "ArtNetMapper");
        t.setDaemon(true);
        return t;
      });
      exec.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.MILLISECONDS);
      exec.scheduleWithFixedDelay(() -> connect(false), 1, 1, TimeUnit.SECONDS);
      shutdownHook = new Thread(this::dispose, "ArtNetMapper-exit");
      Runtime.getRuntime().addShutdownHook(shutdownHook);
    } else {
      exec = null;
      shutdownHook = null;
    }
  }

  // ------------------------------------------------------------------ discovery

  /** Scans for Art-Net nodes (about 1.5 s), prints a numbered list and returns their IPs. */
  public static String[] list() {
    return list(DEFAULT_SCAN_MS);
  }

  /** Scans for Art-Net nodes for timeoutMs, prints a numbered list and returns their IPs. */
  public static String[] list(int timeoutMs) {
    List<Discovery.Node> nodes = Discovery.scan(timeoutMs);
    String[] ips = new String[nodes.size()];
    if (nodes.isEmpty()) {
      System.out.println("ArtNetMapper: No Art-Net nodes found. Check the USB/network cable."
          + " nodes[0] will fail; to run without hardware, pass an IP directly, e.g. new ArtNetMapper(this, \"2.0.0.1\").");
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

  /** True while a node is reachable and frames are being sent. */
  public boolean isConnected() {
    return destination != null;
  }

  /** IP of the node being sent to, or null. */
  public String ip() {
    return connectedIp;
  }

  /**
   * Picks and checks the destination. Runs once at startup and then every second.
   *
   * <ul>
   *   <li>Explicit IP: always sent to. If a local interface is on its subnet the node is
   *       "connected"; if that interface disappears (USB node unplugged) sending pauses so
   *       packets don't leak out the default route. An IP on no local subnet is treated as
   *       routed and sent to anyway.</li>
   * </ul>
   */
  private synchronized void connect(boolean initial) {
    if (closing.get()) return;
    String ip = target;
    connectedIp = ip;

    boolean onLink = Discovery.interfaceFor(ip) != null;
    boolean everOnLink = status == 1 || (status == 3 && wasOnLink);
    if (onLink) {
      wasOnLink = true;
      if (status != 1) {
        destination = Discovery.parse(ip);
        pending = true;
        status = 1;
        System.out.println("ArtNetMapper: Art-Net node " + ip + " connected (" + Discovery.interfaceFor(ip).iface + ")");
      }
    } else if (everOnLink) {
      if (status != 3) {
        destination = null;
        status = 3;
        System.out.println("ArtNetMapper: Art-Net node " + ip + " lost (its network interface disappeared). Waiting for it to come back.");
      }
    } else if (status != 2) {
      destination = Discovery.parse(ip);
      pending = true;
      status = 2;
      System.out.println("ArtNetMapper: sending to " + ip + ". It is not on any local network interface, so packets"
          + " go through the default route. If it's a USB node, check that it's plugged in.");
    }
  }

  private volatile boolean wasOnLink;

  // ------------------------------------------------------------------ fixtures

  /** Defines a channel layout, e.g. {@code "dim=255, r, g, b, w, strobe=0"}. */
  public FixtureType type(String spec) {
    return new FixtureType(spec);
  }

  /** A single-element fixture (par) at universe 0. */
  public Fixture fixture(FixtureType type, int channel) {
    return fixture(type, 0, channel);
  }

  /** A single-element fixture (par). */
  public Fixture fixture(FixtureType type, int universe, int channel) {
    return add(new Fixture(type, universe, channel, Fixture.PAR, 1, 1));
  }

  /** A line of {@code count} elements at universe 0. Place it with {@link Fixture#line}. */
  public Fixture strip(FixtureType type, int channel, int count) {
    return strip(type, 0, channel, count);
  }

  /** A line of {@code count} elements. Place it with {@link Fixture#line}. */
  public Fixture strip(FixtureType type, int universe, int channel, int count) {
    if (count < 1) throw new IllegalArgumentException("ArtNetMapper: strip needs at least 1 element");
    return add(new Fixture(type, universe, channel, Fixture.STRIP, count, 1));
  }

  /** A grid of cols x rows elements at universe 0. Place it with {@link Fixture#rect}. */
  public Fixture matrix(FixtureType type, int channel, int cols, int rows) {
    return matrix(type, 0, channel, cols, rows);
  }

  /** A grid of cols x rows elements. Place it with {@link Fixture#rect}. */
  public Fixture matrix(FixtureType type, int universe, int channel, int cols, int rows) {
    if (cols < 1 || rows < 1) throw new IllegalArgumentException("ArtNetMapper: matrix needs at least 1x1 elements");
    return add(new Fixture(type, universe, channel, Fixture.MATRIX, cols, rows));
  }

  private Fixture add(Fixture f) {
    if (f.type == null) throw new IllegalArgumentException("ArtNetMapper: fixture type is null");
    if (f.universe < 0 || f.universe > 0x7FFF) {
      throw new IllegalArgumentException("ArtNetMapper: universe must be 0-32767, got " + f.universe);
    }
    if (f.channel < 1 || f.channel > 512) {
      throw new IllegalArgumentException("ArtNetMapper: channel must be 1-512, got " + f.channel);
    }
    if (f.lastChannel() > 512) {
      String what = f.kind == Fixture.PAR ? "fixture" : f.kind == Fixture.STRIP ? "strip" : "matrix";
      throw new IllegalArgumentException("ArtNetMapper: " + what + " at u" + f.universe + " ch" + f.channel
          + " needs " + f.channelCount() + " channels, only " + (512 - f.channel + 1)
          + " left in the universe. Fixtures can't cross universes: start it on another universe.");
    }
    for (Fixture o : fixtures) {
      if (o.universe == f.universe && f.channel <= o.lastChannel() && o.channel <= f.lastChannel()) {
        System.out.println("ArtNetMapper: warning: " + f + " overlaps " + o);
      }
    }
    fixtures.add(f);
    synchronized (frames) {
      frames.computeIfAbsent(f.universe, u -> new byte[512]);
    }
    return f;
  }

  /** All fixtures added so far. */
  public List<Fixture> fixtures() {
    return new ArrayList<>(fixtures);
  }

  // ------------------------------------------------------------------ settings

  /** Samples colors from this buffer instead of the main canvas. null = main canvas. */
  public ArtNetMapper source(PGraphics pg) {
    this.source = pg;
    return this;
  }

  /** Global gamma (default 2.5). 1.0 disables correction. */
  public ArtNetMapper gamma(float g) {
    if (!(g > 0)) throw new IllegalArgumentException("ArtNetMapper: gamma must be > 0");
    gamma = g;
    gammaLut = ColorMath.gammaTable(g);
    return this;
  }

  /** Current global gamma. */
  public float gamma() {
    return gamma;
  }

  /** Global brightness 0..1, applied after gamma. */
  public ArtNetMapper brightness(float b) {
    brightness = Math.max(0f, Math.min(1f, b));
    return this;
  }

  /** Maximum frames per second sent (default 44). */
  public ArtNetMapper maxRate(float fps) {
    if (!(fps > 0)) throw new IllegalArgumentException("ArtNetMapper: maxRate must be > 0");
    maxRate = fps;
    return this;
  }

  /** true (default): sample and send after every draw(). false: call {@link #send()} yourself. */
  public ArtNetMapper autoSend(boolean on) {
    autoSend = on;
    return this;
  }

  /** Draws a marker at each sample point (default on). */
  public ArtNetMapper showLocations(boolean on) {
    showLocations = on;
    return this;
  }

  /** Sends all channels at 0 when the sketch stops (default on). */
  public ArtNetMapper blackoutOnExit(boolean on) {
    blackoutOnExit = on;
    return this;
  }

  /** Copy of the current values of a universe, channel 1 at index 0 (512 entries). */
  public int[] channels(int universe) {
    int[] out = new int[512];
    synchronized (frames) {
      byte[] f = frames.get(universe);
      if (f != null) for (int i = 0; i < 512; i++) out[i] = f[i] & 0xFF;
    }
    return out;
  }

  // ------------------------------------------------------------------ frame loop

  /** Called by Processing after the sketch's draw(). Not for direct use. */
  public void draw() {
    if (autoSend) {
      update();
    }
    if (showLocations && parent != null) drawLocations(parent.g);
  }

  /** Samples the source now and queues the frame for sending. For autoSend(false). */
  public void send() {
    update();
  }

  void update() {
    PGraphics src = source != null ? source : (parent != null ? parent.g : null);
    if (src == null) return;
    src.loadPixels();
    sample(src.pixels, src.pixelWidth, src.pixelHeight, src.pixelDensity);
  }

  /** Samples a pixel array and writes all fixtures into the universe buffers. */
  void sample(int[] pixels, int pw, int ph, int density) {
    boolean changed = false;
    synchronized (frames) {
      for (Fixture f : fixtures) {
        byte[] buf = frames.get(f.universe);
        FixtureType t = f.type;
        int size = t.size();
        int[] lut = f.gammaLut != null ? f.gammaLut : gammaLut;
        boolean white = t.hasWhite() && f.whiteMode;
        for (int e = 0; e < f.count; e++) {
          if (!f.placed && !f.warnedUnplaced) {
            f.warnedUnplaced = true;
            System.out.println("ArtNetMapper: warning: " + f + " was never placed; call .at(), .line() or .rect(). It samples (0, 0).");
          }
          int c = samplePoint(pixels, pw, ph, density, f.px[e], f.py[e], f.sampleRadius);
          f.sampled[e] = c;
          ColorMath.process((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, white, lut, brightness, rgbw);
          int base = f.channel - 1 + e * size;
          for (int k = 0; k < size; k++) {
            int v;
            if (k == t.r) v = rgbw[0];
            else if (k == t.g) v = rgbw[1];
            else if (k == t.b) v = rgbw[2];
            else if (k == t.w) v = white ? rgbw[3] : Math.max(0, f.constants[k]);
            else v = f.constants[k];
            if (buf[base + k] != (byte) v) {
              buf[base + k] = (byte) v;
              changed = true;
            }
          }
        }
      }
    }
    if (changed) pending = true;
  }

  /** Average color (0xRRGGBB) of a square around (x, y) in sketch coordinates. Outside = black. */
  static int samplePoint(int[] pixels, int pw, int ph, int density, float x, float y, int radius) {
    int cx = Math.round(x * density), cy = Math.round(y * density);
    int r = radius * density;
    // Points up to one pixel past the edge (e.g. line(0, y, width, y)) read the edge pixel;
    // points further outside read black.
    if (cx < -1 || cy < -1 || cx > pw || cy > ph) return 0;
    long sr = 0, sg = 0, sb = 0;
    int n = 0;
    for (int yy = cy - r; yy <= cy + r; yy++) {
      for (int xx = cx - r; xx <= cx + r; xx++) {
        n++;
        int sx = Math.max(0, Math.min(pw - 1, xx)), sy = Math.max(0, Math.min(ph - 1, yy));
        int c = pixels[sy * pw + sx];
        sr += (c >> 16) & 0xFF;
        sg += (c >> 8) & 0xFF;
        sb += c & 0xFF;
      }
    }
    return (int) ((sr / n) << 16 | (sg / n) << 8 | (sb / n));
  }

  private void drawLocations(PGraphics g) {
    g.pushStyle();
    g.pushMatrix();
    if (g.is3D()) {
      g.camera();
      g.hint(PApplet.DISABLE_DEPTH_TEST);
    } else {
      g.resetMatrix();
    }
    g.noFill();
    g.strokeWeight(1);
    g.ellipseMode(PApplet.CENTER);
    for (Fixture f : fixtures) {
      // Ring radius clears the sample square (half-diagonal r*sqrt2) by 4 px, so markers
      // left on a canvas that is never cleared are not sampled back next frame.
      float inner = 2 * (f.sampleRadius * 1.415f + 4.5f);
      for (int e = 0; e < f.count; e++) {
        float x = f.px[e], y = f.py[e];
        g.stroke(0);
        g.ellipse(x, y, inner + 3, inner + 3);
        g.stroke(0xFF000000 | f.sampled[e]);
        g.ellipse(x, y, inner, inner);
      }
    }
    if (g.is3D()) g.hint(PApplet.ENABLE_DEPTH_TEST);
    g.popMatrix();
    g.popStyle();
  }

  /** Sender thread: honours maxRate, resends every second when idle. */
  private void tick() {
    if (closing.get() || destination == null) return;
    long now = System.nanoTime();
    long since = now - lastSendNanos;
    long minInterval = (long) (1e9 / maxRate);
    if ((pending && since >= minInterval) || since >= 1_000_000_000L) {
      pending = false;
      sendFrames();
    }
  }

  private void sendFrames() {
    InetAddress dst = destination;
    if (dst == null || socket == null) return;
    synchronized (sendLock) {
      List<byte[]> packets = new ArrayList<>();
      synchronized (frames) {
        for (Map.Entry<Integer, byte[]> e : frames.entrySet()) {
          int u = e.getKey();
          int seq = sequence.getOrDefault(u, 0) % 255 + 1;
          sequence.put(u, seq);
          packets.add(ArtNetPackets.artDmx(seq, u, e.getValue(), 512));
        }
      }
      for (byte[] p : packets) {
        try {
          socket.send(new DatagramPacket(p, p.length, dst, ArtNetPackets.PORT));
        } catch (IOException ex) {
          // interface went away; the connection check will notice
        }
      }
      lastSendNanos = System.nanoTime();
    }
  }

  /** Sets every channel to 0 and sends immediately. */
  public void blackout() {
    synchronized (frames) {
      for (byte[] f : frames.values()) java.util.Arrays.fill(f, (byte) 0);
    }
    sendFrames();
  }

  /** Called by Processing when the sketch stops. Not for direct use. */
  public void dispose() {
    if (!closing.compareAndSet(false, true)) return; // stops the sender thread; runs once
    if (exec != null) exec.shutdown();
    if (blackoutOnExit && destination != null) {
      for (int i = 0; i < 3; i++) {
        blackout();
        try {
          Thread.sleep(30);
        } catch (InterruptedException e) {
          break;
        }
      }
    }
    disposed = true;
    if (exec != null) exec.shutdownNow();
    if (socket != null) socket.close();
    if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException e) {
        // already shutting down
      }
    }
  }
}
