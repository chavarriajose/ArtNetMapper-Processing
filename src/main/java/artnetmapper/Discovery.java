package artnetmapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds Art-Net nodes two ways:
 * <ol>
 *   <li>ArtPoll broadcast on every IPv4 interface; nodes that reply are listed.</li>
 *   <li>Interface scan: on small point-to-point links (an interface without a default
 *       route whose subnet holds exactly one other host, typical of USB-to-DMX nodes
 *       that appear as a virtual Ethernet adapter) the single peer is pinged and
 *       listed as "guessed" if it answers. Large networks are never swept.</li>
 * </ol>
 */
final class Discovery {

  private Discovery() {}

  /** A discovered node. */
  static final class Node {
    final String ip;
    final String name;
    final String iface;
    final boolean artPoll;

    Node(String ip, String name, String iface, boolean artPoll) {
      this.ip = ip;
      this.name = name;
      this.iface = iface;
      this.artPoll = artPoll;
    }

    /** e.g. {@code Electroconcept USB NODE  (answered ArtPoll, en6)}. */
    String label() {
      StringBuilder s = new StringBuilder();
      if (name != null && !name.isEmpty()) s.append(name).append("  ");
      s.append(artPoll ? "(answered ArtPoll" : "(found by interface scan, no ArtPoll reply");
      if (iface != null) s.append(", ").append(iface);
      return s.append(')').toString();
    }
  }

  /** One local IPv4 address with its interface. */
  static final class LocalAddr {
    final String iface;
    final InetAddress addr;
    final InetAddress broadcast;
    final int prefix;

    LocalAddr(String iface, InetAddress addr, InetAddress broadcast, int prefix) {
      this.iface = iface;
      this.addr = addr;
      this.broadcast = broadcast;
      this.prefix = prefix;
    }

    boolean contains(InetAddress ip) {
      return sameSubnet(addr, ip, prefix);
    }
  }

  static List<LocalAddr> localAddresses() {
    List<LocalAddr> out = new ArrayList<>();
    try {
      for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!ni.isUp() || ni.isLoopback()) continue;
        for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
          if (ia.getAddress() instanceof Inet4Address) {
            out.add(new LocalAddr(ni.getName(), ia.getAddress(), ia.getBroadcast(), ia.getNetworkPrefixLength()));
          }
        }
      }
    } catch (SocketException e) {
      // no interfaces visible: return what we have
    }
    return out;
  }

  /**
   * The local interface whose subnet contains ip (a "loopback" entry for 127.x),
   * or null if no connected network contains it.
   */
  static LocalAddr interfaceFor(String ip) {
    InetAddress target = parse(ip);
    if (target != null && target.isLoopbackAddress()) return new LocalAddr("loopback", target, null, 8);
    return interfaceFor(ip, localAddresses());
  }

  static boolean sameSubnet(InetAddress a, InetAddress b, int prefix) {
    if (prefix <= 0 || prefix > 32) return false;
    int mask = prefix == 32 ? -1 : ~((1 << (32 - prefix)) - 1);
    return (toInt(a) & mask) == (toInt(b) & mask);
  }

  static int toInt(InetAddress a) {
    byte[] b = a.getAddress();
    return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
  }

  static InetAddress parse(String ip) {
    if (ip == null || !ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return null;
    try {
      return InetAddress.getByName(ip);
    } catch (IOException e) {
      return null;
    }
  }

  /** Runs ArtPoll and the interface scan for about timeoutMs and returns nodes found. */
  static List<Node> scan(int timeoutMs) {
    List<LocalAddr> locals = localAddresses();
    Set<String> localIps = new LinkedHashSet<>();
    for (LocalAddr la : locals) localIps.add(la.addr.getHostAddress());

    // Interface scan runs in parallel with ArtPoll listening.
    List<Node> guessed = Collections.synchronizedList(new ArrayList<>());
    Thread scanner = new Thread(() -> guessed.addAll(scanPointToPoint(locals, localIps)), "ArtNetMapper-scan");
    scanner.setDaemon(true);
    scanner.start();

    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    Map<String, Node> polled = new LinkedHashMap<>();
    try (DatagramSocket sock = openPollSocket()) {
      sock.setBroadcast(true);
      sock.setSoTimeout(100);
      byte[] poll = ArtNetPackets.artPoll();
      Set<InetAddress> targets = new LinkedHashSet<>();
      for (LocalAddr la : locals) if (la.broadcast != null) targets.add(la.broadcast);
      InetAddress all = parse("255.255.255.255");
      if (all != null) targets.add(all);
      for (InetAddress t : targets) {
        try {
          sock.send(new DatagramPacket(poll, poll.length, t, ArtNetPackets.PORT));
        } catch (IOException e) {
          // interface may refuse broadcast; ignore
        }
      }
      byte[] buf = new byte[1024];
      while (System.nanoTime() < deadline) {
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        try {
          sock.receive(p);
        } catch (SocketTimeoutException e) {
          continue;
        }
        ArtNetPackets.PollReply r = ArtNetPackets.parsePollReply(p.getData(), p.getLength());
        if (r == null) continue;
        String ip = r.ip;
        if (ip.equals("0.0.0.0")) ip = p.getAddress().getHostAddress();
        if (localIps.contains(ip) || polled.containsKey(ip)) continue; // skip ourselves / other apps on this machine
        String name = !r.longName.isEmpty() ? r.longName : r.shortName;
        LocalAddr la = interfaceFor(ip, locals);
        polled.put(ip, new Node(ip, name, la == null ? null : la.iface, true));
      }
    } catch (IOException e) {
      System.out.println("ArtNetMapper: warning: ArtPoll could not be sent (" + e.getMessage()
          + "). Only the interface scan was used.");
    }

    try {
      // ArtPoll listening is over; give the interface scan up to 2 s more to finish its
      // commands (arp, netstat, ipconfig, ping each time out on their own).
      scanner.join(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    List<Node> out = new ArrayList<>(polled.values());
    synchronized (guessed) {
      for (Node g : guessed) if (!polled.containsKey(g.ip)) out.add(g);
    }
    return out;
  }

  private static LocalAddr interfaceFor(String ip, List<LocalAddr> locals) {
    InetAddress a = parse(ip);
    if (a == null) return null;
    for (LocalAddr la : locals) if (la.contains(a)) return la;
    return null;
  }

  /**
   * Socket for ArtPoll. Nodes reply to UDP 6454, so try to share that port
   * (SO_REUSEADDR/SO_REUSEPORT). If another app holds it exclusively, fall back
   * to an ephemeral port: nodes that reply to the source port are still found.
   */
  private static DatagramSocket openPollSocket() throws SocketException {
    DatagramSocket s = new DatagramSocket(null);
    try {
      s.setReuseAddress(true);
      try {
        s.setOption(StandardSocketOptions.SO_REUSEPORT, true);
      } catch (UnsupportedOperationException | IOException e) {
        // not supported on this platform
      }
      s.bind(new InetSocketAddress(ArtNetPackets.PORT));
      return s;
    } catch (SocketException e) {
      s.close();
      DatagramSocket eph = new DatagramSocket(null);
      eph.setReuseAddress(true);
      eph.bind(new InetSocketAddress(0));
      return eph;
    }
  }

  // ---------------------------------------------------------------- interface scan

  private static List<Node> scanPointToPoint(List<LocalAddr> locals, Set<String> localIps) {
    List<Node> out = new ArrayList<>();
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    boolean mac = os.contains("mac");
    boolean linux = os.contains("linux");
    if (!mac && !linux) return out; // interface scan not implemented on this OS yet

    Set<String> defaultIfaces = new LinkedHashSet<>();
    Set<String> gateways = new LinkedHashSet<>();
    readDefaultRoutes(mac, defaultIfaces, gateways);
    Map<String, Set<String>> neighbours = readNeighbours();

    for (LocalAddr la : locals) {
      if (defaultIfaces.contains(la.iface)) continue; // main network (Wi-Fi/LAN): never sweep
      if (la.prefix < 8) continue;
      Set<String> peers = new LinkedHashSet<>();
      Set<String> n = neighbours.get(la.iface);
      if (n != null) peers.addAll(n);
      if (mac) {
        String dhcp = run(1000, "ipconfig", "getoption", la.iface, "server_identifier").trim();
        if (!dhcp.isEmpty()) peers.add(dhcp);
      }
      peers.removeIf(ip -> {
        InetAddress a = parse(ip);
        return a == null || localIps.contains(ip) || gateways.contains(ip) || a.isMulticastAddress()
            || !la.contains(a) || (la.broadcast != null && ip.equals(la.broadcast.getHostAddress()))
            || ip.endsWith(".255") || ip.endsWith(".0");
      });
      if (peers.size() != 1) continue;
      String peer = peers.iterator().next();
      if (ping(peer, mac)) out.add(new Node(peer, null, la.iface, false));
    }
    return out;
  }

  private static final Pattern ARP = Pattern.compile("\\((\\d+\\.\\d+\\.\\d+\\.\\d+)\\) at (\\S+) (?:\\[\\w+\\] )?on (\\S+)");

  /** Parses `arp -an`: complete entries per interface. */
  static Map<String, Set<String>> readNeighbours() {
    return parseArp(run(1500, "arp", "-an"));
  }

  static Map<String, Set<String>> parseArp(String text) {
    Map<String, Set<String>> map = new LinkedHashMap<>();
    for (String line : text.split("\n")) {
      Matcher m = ARP.matcher(line);
      if (!m.find()) continue;
      String mac = m.group(2);
      if (mac.contains("incomplete") || mac.equalsIgnoreCase("ff:ff:ff:ff:ff:ff")) continue;
      map.computeIfAbsent(m.group(3), k -> new LinkedHashSet<>()).add(m.group(1));
    }
    return map;
  }

  private static void readDefaultRoutes(boolean mac, Set<String> ifaces, Set<String> gateways) {
    if (mac) {
      parseMacRoutes(run(1500, "netstat", "-rn", "-f", "inet"), ifaces, gateways);
    } else {
      for (String line : run(1500, "ip", "route", "show", "default").split("\n")) {
        String[] t = line.trim().split("\\s+");
        for (int i = 0; i + 1 < t.length; i++) {
          if (t[i].equals("via")) gateways.add(t[i + 1]);
          if (t[i].equals("dev")) ifaces.add(t[i + 1]);
        }
      }
    }
  }

  static void parseMacRoutes(String text, Set<String> ifaces, Set<String> gateways) {
    for (String line : text.split("\n")) {
      String[] t = line.trim().split("\\s+");
      if (t.length >= 4 && t[0].equals("default")) {
        if (t[1].matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) gateways.add(t[1]);
        ifaces.add(t[3]);
      }
    }
  }

  private static boolean ping(String ip, boolean mac) {
    String out = mac ? run(2000, "ping", "-c", "1", "-t", "1", ip)
                     : run(2000, "ping", "-c", "1", "-W", "1", ip);
    if (out.contains(" 0.0% packet loss") || out.contains(" 0% packet loss")) return true;
    try {
      return InetAddress.getByName(ip).isReachable(500);
    } catch (IOException e) {
      return false;
    }
  }

  /** Runs a command, returns stdout ("" on any failure or timeout). */
  static String run(long timeoutMs, String... cmd) {
    try {
      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      p.getOutputStream().close();
      StringBuilder sb = new StringBuilder();
      Thread reader = new Thread(() -> {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
          String line;
          while ((line = r.readLine()) != null) {
            synchronized (sb) {
              sb.append(line).append('\n');
            }
          }
        } catch (IOException e) {
          // ignore
        }
      });
      reader.setDaemon(true);
      reader.start();
      if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        p.destroyForcibly();
        return "";
      }
      reader.join(200);
      synchronized (sb) {
        return sb.toString();
      }
    } catch (IOException e) {
      return "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    }
  }
}
