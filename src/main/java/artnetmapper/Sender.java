package artnetmapper;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * Owns the universe buffers and everything about getting them onto the network:
 * the UDP socket, the send thread (rate cap, 1 s keepalive), sequence numbers,
 * the connection check and the exit blackout.
 *
 * <p>Writers (the sketch thread) change universe data while holding {@link #lock}
 * and then call {@link #changed()}. The send thread sleeps until the next frame is
 * due, so an idle sketch costs almost nothing.
 */
final class Sender {

  /** Where packets go. Tests pass a fake; the library passes a UDP socket. */
  interface Sink {
    void send(byte[] packet, InetAddress to) throws IOException;
  }

  /** One DMX universe: 512 values plus its own ArtDmx packet and sequence counter. */
  static final class Universe {
    final int id;
    final byte[] data = new byte[512];
    private final byte[] packet;
    private int seq;

    Universe(int id) {
      this.id = id;
      this.packet = ArtNetPackets.artDmx(1, id, data, 512); // header filled once
    }

    /** Next ArtDmx packet: sequence 1..255, current data or all zeros. Call with the lock held. */
    byte[] nextPacket(boolean black) {
      seq = seq % 255 + 1;
      packet[12] = (byte) seq;
      if (black) Arrays.fill(packet, ArtNetPackets.DMX_HEADER, ArtNetPackets.DMX_HEADER + 512, (byte) 0);
      else System.arraycopy(data, 0, packet, ArtNetPackets.DMX_HEADER, 512);
      return packet;
    }
  }

  static final long KEEPALIVE_NANOS = 1_000_000_000L;
  static final long CHECK_NANOS = 1_000_000_000L;

  /** Guards universe data, {@code pending} and the send itself. */
  final Object lock = new Object();
  private final Map<Integer, Universe> universes = new TreeMap<>();

  private final String ip;
  private final InetAddress address;
  private final Sink sink;
  private final DatagramSocket socket;
  private final Thread thread;

  private volatile InetAddress destination;
  private volatile float maxRate = 44f;
  private boolean pending;          // guarded by lock
  private long lastSend = Long.MIN_VALUE / 2;
  private volatile boolean closed;
  private boolean black;            // guarded by lock
  private boolean warnedError;

  /** Connection state, for messages: 0 not checked, 1 on a local link, 2 routed, 3 link lost. */
  private int status;

  /** Network sender for a real sketch: opens a socket and starts the send thread. */
  Sender(String ip, InetAddress address) {
    this.ip = ip;
    this.address = address;
    try {
      socket = new DatagramSocket(); // ephemeral port: never competes for 6454
      socket.setBroadcast(true);
    } catch (SocketException e) {
      throw new RuntimeException("ArtNetMapper: could not open a UDP socket: " + e.getMessage(), e);
    }
    this.sink = (p, to) -> socket.send(new DatagramPacket(p, p.length, to, ArtNetPackets.PORT));
    checkConnection();
    thread = new Thread(this::run, "ArtNetMapper-send");
    thread.setDaemon(true);
    thread.start();
  }

  /** Sender without network or thread, for tests. A null sink sends nothing. */
  Sender(String ip, InetAddress address, Sink sink) {
    this.ip = ip;
    this.address = address;
    this.sink = sink;
    this.socket = null;
    this.thread = null;
    this.destination = sink == null ? null : address;
  }

  // ------------------------------------------------------------------ universes

  /** The universe with this id, created on first use. */
  Universe universe(int id) {
    synchronized (lock) {
      return universes.computeIfAbsent(id, Universe::new);
    }
  }

  Collection<Universe> universes() {
    return universes.values();
  }

  /** Marks the data as changed; the send thread wakes and sends when the rate cap allows. */
  void changed() {
    synchronized (lock) {
      pending = true;
      lock.notifyAll();
    }
  }

  void maxRate(float fps) {
    maxRate = fps;
    synchronized (lock) {
      lock.notifyAll();
    }
  }

  float getMaxRate() {
    return maxRate;
  }

  boolean isSending() {
    return destination != null;
  }

  // ------------------------------------------------------------------ send loop

  private void run() {
    long nextCheck = System.nanoTime() + CHECK_NANOS;
    while (!closed) {
      try {
        long now = System.nanoTime();
        if (now - nextCheck >= 0) {
          checkConnection();
          nextCheck = now + CHECK_NANOS;
        }
        long wait = Math.min(step(now), nextCheck - now);
        synchronized (lock) {
          if (!closed && wait > 0 && !(pending && System.nanoTime() - lastSend >= minInterval())) {
            lock.wait(Math.max(1, wait / 1_000_000), 0);
          }
        }
      } catch (InterruptedException e) {
        return;
      } catch (RuntimeException e) {
        if (!warnedError) {
          warnedError = true;
          System.out.println("ArtNetMapper: send error, still trying: " + e);
        }
        sleepQuietly(100);
      }
    }
  }

  private long minInterval() {
    return (long) (1e9 / maxRate);
  }

  /**
   * Sends if a frame is due at time {@code now} (nanoseconds) and returns the
   * nanoseconds until the next one could be due. Package-private for tests.
   */
  long step(long now) {
    if (destination == null) return CHECK_NANOS;
    synchronized (lock) {
      long interval = pending ? minInterval() : KEEPALIVE_NANOS;
      long due = lastSend + interval;
      if (now - due >= 0) {
        pending = false;
        sendAllLocked();
        // Keep to the schedule when we woke a little late, so the average rate is
        // exactly maxRate; after a long pause, start again from now.
        lastSend = now - due < interval ? due : now;
        due = lastSend + KEEPALIVE_NANOS;
      }
      return due - now;
    }
  }

  private void sendAllLocked() {
    InetAddress to = destination;
    if (to == null || sink == null) return;
    for (Universe u : universes.values()) {
      try {
        sink.send(u.nextPacket(black), to);
      } catch (IOException e) {
        // interface went away; the connection check will notice
      }
    }
  }

  // ------------------------------------------------------------------ connection

  /**
   * The IP is always sent to, except when it was on a local link (e.g. a USB node's
   * interface) and that link disappeared: then sending pauses until it returns, so
   * packets don't go out through the default route.
   */
  void checkConnection() {
    Discovery.LocalAddr link = Discovery.interfaceFor(ip);
    int next = nextStatus(status, link != null);
    if (next == status) return;
    if (next == 1) {
      destination = address;
      String via = link.iface.equals("loopback") ? "this computer" : link.iface;
      System.out.println("ArtNetMapper: " + (status == 3 ? "reconnected to " : "connected to ") + ip + " (" + via + ")");
      changed();
    } else if (next == 2) {
      destination = address;
      System.out.println("ArtNetMapper: " + ip + " is not on any connected network. Sending anyway."
          + " If it's a USB node, check that it's plugged in.");
      changed();
    } else {
      destination = null;
      System.out.println("ArtNetMapper: lost " + ip + " (its network is gone; unplugged?). Sending paused until it is back.");
    }
    status = next;
  }

  /**
   * Connection state machine: 0 not checked, 1 on a local link, 2 routed (never on a
   * local link), 3 link lost.
   */
  static int nextStatus(int status, boolean onLink) {
    if (onLink) return 1;
    return status == 1 || status == 3 ? 3 : 2;
  }

  // ------------------------------------------------------------------ shutdown

  /** Blackout switch: while on, every packet carries zeros; the universe data is kept. */
  void blackout(boolean on) {
    synchronized (lock) {
      black = on;
      pending = true;
      lock.notifyAll();
    }
  }

  boolean isBlackout() {
    synchronized (lock) {
      return black;
    }
  }

  /** Sends one frame now (zeros if the blackout is on). */
  void sendNow() {
    synchronized (lock) {
      sendAllLocked();
      lastSend = System.nanoTime();
      pending = false;
    }
  }

  boolean isClosed() {
    return closed;
  }

  /** Stops the send thread, optionally sends a blackout (3 frames), closes the socket. */
  void close(boolean blackout) {
    synchronized (lock) {
      if (closed) return;
      closed = true;
      lock.notifyAll();
    }
    if (thread != null) {
      try {
        thread.join(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (blackout && destination != null) {
      synchronized (lock) {
        black = true;
      }
      for (int i = 0; i < 3; i++) {
        sendNow();
        sleepQuietly(30);
      }
    }
    if (socket != null) socket.close();
  }

  private static void sleepQuietly(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
