// Loopback test: listens on 127.0.0.1:6454, runs LoopbackSketch through the Processing
// CLI, then checks the ArtDmx packets it sent.
// Run: java tests/loopback/LoopbackCheck.java      (needs `gradle installLibrary` first)
import java.io.File;
import java.net.*;
import java.util.*;

public class LoopbackCheck {
  record Pkt(long t, int universe, int seq, int[] data) {}

  static int failures = 0;

  public static void main(String[] args) throws Exception {
    // The Processing CLI wrapper splits paths on spaces, so run a copy from a temp dir.
    File tmp = java.nio.file.Files.createTempDirectory("anm").toFile();
    File sketch = new File(tmp, "LoopbackSketch");
    sketch.mkdirs();
    java.nio.file.Files.copy(new File("tests/loopback/LoopbackSketch/LoopbackSketch.pde").toPath(),
        new File(sketch, "LoopbackSketch.pde").toPath());
    DatagramSocket sock = new DatagramSocket(null);
    sock.setReuseAddress(true);
    try { sock.setOption(StandardSocketOptions.SO_REUSEPORT, true); } catch (Exception ignored) {}
    sock.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 6454));
    sock.setSoTimeout(200);

    Process p = new ProcessBuilder("/usr/local/bin/processing", "cli", "--sketch=" + sketch, "--run")
        .redirectErrorStream(true).redirectOutput(new File("build/loopback-sketch.log")).start();

    List<Pkt> pkts = new ArrayList<>();
    long start = System.nanoTime(), lastPkt = 0;
    byte[] buf = new byte[1024];
    while (true) {
      long now = System.nanoTime();
      if ((now - start) / 1e9 > 90) { System.out.println("TIMEOUT waiting for sketch"); p.destroyForcibly(); break; }
      if (!p.isAlive() && lastPkt != 0 && (now - lastPkt) / 1e9 > 1.5) break;
      if (!p.isAlive() && lastPkt == 0 && (now - start) / 1e9 > 60) break;
      DatagramPacket d = new DatagramPacket(buf, buf.length);
      try { sock.receive(d); } catch (SocketTimeoutException e) { continue; }
      byte[] b = d.getData();
      if (d.getLength() < 18 || b[8] != 0x00 || b[9] != 0x50) continue;
      int u = (b[14] & 0xFF) | ((b[15] & 0x7F) << 8);
      int len = ((b[16] & 0xFF) << 8) | (b[17] & 0xFF);
      int[] data = new int[len];
      for (int i = 0; i < len; i++) data[i] = b[18 + i] & 0xFF;
      lastPkt = System.nanoTime();
      pkts.add(new Pkt(lastPkt, u, b[12] & 0xFF, data));
    }
    System.out.println("sketch exit code: " + (p.isAlive() ? "running" : p.exitValue()) + ", packets: " + pkts.size());

    check("received packets", !pkts.isEmpty());
    Map<Integer, List<Pkt>> byU = new TreeMap<>();
    for (Pkt k : pkts) byU.computeIfAbsent(k.universe, x -> new ArrayList<>()).add(k);
    check("universes 0,1,2,3 only", byU.keySet().equals(Set.of(0, 1, 2, 3)), "got " + byU.keySet());

    Map<Integer, int[]> expected = Map.of(
        0, new int[] {0, 0, 0, 255, 255, 150, 50, 0, 50, 77},
        1, new int[] {255, 255, 255, 1, 2, 3, 200, 100, 50},
        2, new int[] {255, 255, 255, 200, 100, 50, 0, 0, 255, 0, 255, 0});

    for (var e : byU.entrySet()) {
      int u = e.getKey();
      List<Pkt> list = e.getValue();
      check("u" + u + " length 512", list.stream().allMatch(k -> k.data.length == 512));
      // last steady (non-blackout) frame
      Pkt steady = null;
      for (Pkt k : list) if (Arrays.stream(k.data).anyMatch(v -> v != 0)) steady = k;
      check("u" + u + " has a lit frame", steady != null);
      if (steady != null && expected.containsKey(u)) {
        int[] exp = expected.get(u);
        int[] got = Arrays.copyOf(steady.data, exp.length);
        check("u" + u + " values", Arrays.equals(exp, got), "expected " + Arrays.toString(exp) + " got " + Arrays.toString(got));
        boolean restZero = true;
        for (int i = exp.length; i < 512; i++) if (steady.data[i] != 0) restZero = false;
        check("u" + u + " unused channels are 0", restZero);
      }
      Pkt last = list.get(list.size() - 1);
      check("u" + u + " blackout on exit", Arrays.stream(last.data).allMatch(v -> v == 0));
      boolean seqOk = true;
      for (int i = 0; i < list.size(); i++) {
        int s = list.get(i).seq;
        if (s < 1 || s > 255) seqOk = false;
        if (i > 0) { int prev = list.get(i - 1).seq; if (s != prev % 255 + 1) { if (seqOk) System.out.println("  u" + u + " seq break at packet " + i + "/" + list.size() + ": " + prev + " -> " + s); seqOk = false; } }
      }
      check("u" + u + " sequence 1..255, +1 each packet", seqOk);
      // rate over the lit period
      List<Pkt> lit = list.stream().filter(k -> Arrays.stream(k.data).anyMatch(v -> v != 0)).toList();
      if (lit.size() > 10) {
        double secs = (lit.get(lit.size() - 1).t - lit.get(0).t) / 1e9;
        double fps = (lit.size() - 1) / secs;
        System.out.printf("  u%d: %d lit packets over %.2f s = %.1f fps%n", u, lit.size(), secs, fps);
        check("u" + u + " rate <= 45 fps", fps <= 45.5, String.format("%.1f fps", fps));
        check("u" + u + " rate >= 40 fps (sketch runs 60, cap 44)", fps >= 40, String.format("%.1f fps", fps));
      }
    }
    System.out.println(failures == 0 ? "LOOPBACK PASS" : "LOOPBACK FAIL (" + failures + ")");
    System.exit(failures == 0 ? 0 : 1);
  }

  static void check(String name, boolean ok) { check(name, ok, ""); }
  static void check(String name, boolean ok, String detail) {
    System.out.println((ok ? "  ok   " : "  FAIL ") + name + (ok || detail.isEmpty() ? "" : "  -- " + detail));
    if (!ok) failures++;
  }
}
