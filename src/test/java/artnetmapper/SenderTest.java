package artnetmapper;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The send logic, driven with a fake clock and a fake network. */
class SenderTest {

  private static final long MS = 1_000_000L;

  private final List<byte[]> sent = new ArrayList<>();

  private Sender sender() throws Exception {
    return new Sender("127.0.0.1", InetAddress.getByName("127.0.0.1"), (p, to) -> sent.add(p.clone()));
  }

  @Test
  void sendsChangedFrameThenWaitsForRateCap() throws Exception {
    Sender s = sender();
    s.universe(0);
    s.changed();
    long now = 1_000 * MS;
    s.step(now);
    assertEquals(1, sent.size());
    s.changed();
    long wait = s.step(now + 5 * MS);           // 44 fps -> 22.7 ms between frames
    assertEquals(1, sent.size());
    assertTrue(wait > 15 * MS && wait < 20 * MS, "wait " + wait);
    s.step(now + 23 * MS);
    assertEquals(2, sent.size());
  }

  @Test
  void keepaliveEverySecondWhenIdle() throws Exception {
    Sender s = sender();
    s.universe(0);
    long now = 1_000 * MS;
    s.step(now);                                // first frame goes out (never sent before)
    assertEquals(1, sent.size());
    assertEquals(1000 * MS, s.step(now + 0));   // nothing pending: next due in 1 s
    s.step(now + 999 * MS);
    assertEquals(1, sent.size());
    s.step(now + 1000 * MS);
    assertEquals(2, sent.size());
  }

  @Test
  void sequencePerUniverseRuns1To255() throws Exception {
    Sender s = sender();
    s.universe(0);
    s.universe(3);
    long now = 1_000 * MS;
    for (int i = 0; i < 300; i++) {
      s.changed();
      s.step(now + i * 30 * MS);
    }
    assertEquals(600, sent.size());
    for (int i = 0; i < sent.size(); i += 2) {
      int expected = (i / 2) % 255 + 1;
      assertEquals(expected, sent.get(i)[12] & 0xFF);       // universe 0
      assertEquals(expected, sent.get(i + 1)[12] & 0xFF);   // universe 3
      assertEquals(3, sent.get(i + 1)[14]);
    }
  }

  @Test
  void packetCarriesCurrentData() throws Exception {
    Sender s = sender();
    Sender.Universe u = s.universe(2);
    synchronized (s.lock) {
      u.data[0] = 7;
      u.data[511] = (byte) 200;
    }
    s.changed();
    s.step(1_000 * MS);
    byte[] p = sent.get(0);
    assertEquals(7, p[18]);
    assertEquals(200, p[18 + 511] & 0xFF);
    assertEquals(2, p[14]);
  }

  @Test
  void blackoutSwitchSendsZerosButKeepsData() throws Exception {
    Sender s = sender();
    Sender.Universe u = s.universe(0);
    u.data[5] = 9;
    s.blackout(true);
    assertTrue(s.isBlackout());
    long now = 1_000 * MS;
    s.step(now);
    assertEquals(0, sent.get(0)[18 + 5]);        // sent as 0
    assertEquals(9, u.data[5]);                  // value kept
    s.changed();
    s.step(now + 30 * MS);
    assertEquals(0, sent.get(1)[18 + 5]);        // stays black while data changes
    s.blackout(false);
    s.step(now + 60 * MS);
    assertEquals(9, sent.get(2)[18 + 5]);        // back to the current value at once
  }

  @Test
  void connectionStates() {
    assertEquals(1, Sender.nextStatus(0, true));    // first check, on a link
    assertEquals(2, Sender.nextStatus(0, false));   // first check, routed
    assertEquals(3, Sender.nextStatus(1, false));   // link lost
    assertEquals(3, Sender.nextStatus(3, false));   // still lost
    assertEquals(1, Sender.nextStatus(3, true));    // back
    assertEquals(2, Sender.nextStatus(2, false));   // routed stays routed
  }
}
