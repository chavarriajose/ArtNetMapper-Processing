package artnetmapper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

class FixtureTest {

  /** Mapper with no sketch and no network, for pure logic tests. */
  private static ArtNetMapper mapper() {
    return new ArtNetMapper(null, "2.0.0.1", false);
  }

  @Test
  void parsesTypeWithConstants() {
    FixtureType t = new FixtureType("dim=255, R, g, b, w, strobe=0, mode");
    assertEquals(7, t.size());
    assertArrayEquals(new int[] {255, -1, -1, -1, -1, 0, 0}, t.defaults);
    assertEquals(1, t.r);
    assertEquals(4, t.w);
    assertEquals(5, t.indexOf("STROBE"));
  }

  @Test
  void colorChannelWithValueIsConstant() {
    FixtureType t = new FixtureType("r, g, b, w=0");
    assertFalse(t.hasWhite());
    assertEquals(0, t.defaults[3]);
  }

  @Test
  void typeErrors() {
    assertThrows(IllegalArgumentException.class, () -> new FixtureType(""));
    assertThrows(IllegalArgumentException.class, () -> new FixtureType("r, r"));
    assertThrows(IllegalArgumentException.class, () -> new FixtureType("dim=300"));
    assertThrows(IllegalArgumentException.class, () -> new FixtureType("dim=abc"));
    assertThrows(IllegalArgumentException.class, () -> new FixtureType("=5"));
  }

  @Test
  void setAndGetConstant() {
    ArtNetMapper m = mapper();
    Fixture f = m.fixture(m.type("dim=255, r, g, b, strobe=0"), 1);
    f.set("strobe", 128);
    assertEquals(128, f.get("strobe"));
    f.set("dim", 999);
    assertEquals(255, f.get("dim"));
    assertThrows(IllegalArgumentException.class, () -> f.set("r", 10));
    assertThrows(IllegalArgumentException.class, () -> f.set("pan", 10));
  }

  @Test
  void pastChannel512Throws() {
    ArtNetMapper m = mapper();
    FixtureType rgb = m.type("r, g, b");
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> m.matrix(rgb, 0, 400, 16, 16));
    assertTrue(e.getMessage().contains("u0 ch400 needs 768 channels, only 113 left"), e.getMessage());
    assertThrows(IllegalArgumentException.class, () -> m.strip(rgb, 0, 511, 1)); // ch511-513
  }

  @Test
  void exactFitAtEndOfUniverse() {
    ArtNetMapper m = mapper();
    FixtureType rgb = m.type("r, g, b");
    Fixture f = m.strip(rgb, 0, 1, 170);
    assertEquals(510, f.lastChannel());
    Fixture g = m.fixture(m.type("a, b"), 511);
    assertEquals(512, g.lastChannel());
    assertThrows(IllegalArgumentException.class, () -> m.fixture(rgb, 511));
    assertThrows(IllegalArgumentException.class, () -> m.fixture(rgb, 0));
    assertThrows(IllegalArgumentException.class, () -> m.fixture(rgb, -1, 1));
  }

  @Test
  void overlapOnlyWarns() {
    ArtNetMapper m = mapper();
    FixtureType par = m.type("r, g, b, w");
    PrintStream old = System.out;
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buf));
    try {
      m.fixture(par, 1);
      m.fixture(par, 3);
      m.fixture(par, 1, 3); // other universe: no warning
    } finally {
      System.setOut(old);
    }
    String out = buf.toString();
    assertTrue(out.contains("warning"), out);
    assertEquals(1, out.split("warning", -1).length - 1, out);
    assertEquals(3, m.fixtures().size());
  }

  @Test
  void lineSpreadsFromFirstToLast() {
    ArtNetMapper m = mapper();
    Fixture s = m.strip(m.type("r, g, b"), 1, 5).line(0, 10, 100, 10);
    assertEquals(0, s.x(0), 1e-4);
    assertEquals(25, s.x(1), 1e-4);
    assertEquals(100, s.x(4), 1e-4);
    Fixture rev = m.strip(m.type("r, g, b"), 100, 3).line(100, 0, 0, 0);
    assertEquals(100, rev.x(0), 1e-4);
    assertEquals(0, rev.x(2), 1e-4);
  }

  @Test
  void rectOnlyForMatrix() {
    ArtNetMapper m = mapper();
    Fixture p = m.fixture(m.type("r, g, b"), 1);
    assertThrows(IllegalStateException.class, () -> p.rect(0, 0, 10, 10));
  }
}
