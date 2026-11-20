package artnetmapper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the v0.1 QC review findings (L1-L4, H2, M5 change detection).
 */
class QcReviewTest {

  private static ArtNetMapper mapper() {
    return new ArtNetMapper(null, "2.0.0.1", false);
  }

  /** L1: with whiteMode(false), w can be set from code and defaults to 0. */
  @Test
  void whiteModeOffAllowsSettingW() {
    ArtNetMapper m = mapper().gamma(1);
    Fixture f = m.fixture(m.type("r, g, b, w"), 1).at(0, 0);
    assertThrows(IllegalArgumentException.class, () -> f.set("w", 100)); // still sampled
    f.whiteMode(false);
    m.sample(new int[] {0xFFFFFFFF}, 1, 1, 1);
    assertEquals(0, m.channels(0)[3]);
    f.set("w", 100);
    m.sample(new int[] {0xFFFFFFFF}, 1, 1, 1);
    assertArrayEquals(new int[] {255, 255, 255, 100}, Arrays.copyOf(m.channels(0), 4));
    assertThrows(IllegalArgumentException.class, () -> f.set("r", 1));
  }

  /** L2: line(0, y, width, y) samples the edge pixel at x == width. */
  @Test
  void lineToWidthReadsEdgePixel() {
    ArtNetMapper m = mapper().gamma(1);
    m.strip(m.type("r, g, b"), 1, 3).line(0, 5, 100, 5);
    int[] px = new int[100 * 10];
    Arrays.fill(px, 0xFFFFFFFF);
    m.sample(px, 100, 10, 1);
    int[] c = m.channels(0);
    assertEquals(255, c[0]);
    assertEquals(255, c[3]);
    assertEquals(255, c[6]);
  }

  /** L3: repeated placeholder names are allowed; set() sets all of them. Colors can't repeat. */
  @Test
  void duplicatePlaceholderNamesAllowed() {
    ArtNetMapper m = mapper();
    Fixture f = m.fixture(m.type("r, g, b, unused, unused"), 1).at(0, 0);
    f.set("unused", 9);
    m.sample(new int[] {0xFF000000}, 1, 1, 1);
    assertEquals(9, m.channels(0)[3]);
    assertEquals(9, m.channels(0)[4]);
    assertThrows(IllegalArgumentException.class, () -> new FixtureType("r, g, b, r"));
  }

  /** L4: a fixture that was never placed prints a warning once. */
  @Test
  void unplacedFixtureWarnsOnce() {
    ArtNetMapper m = mapper();
    m.strip(m.type("r, g, b"), 1, 4); // no line()
    PrintStream old = System.out;
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buf));
    try {
      m.sample(new int[100], 10, 10, 1);
      m.sample(new int[100], 10, 10, 1);
    } finally {
      System.setOut(old);
    }
    String out = buf.toString();
    assertEquals(1, out.split("never placed", -1).length - 1, out);
  }

  /** M5: identical frames don't mark the output as changed. */
  @Test
  void unchangedFrameIsNotPending() throws Exception {
    ArtNetMapper m = mapper();
    m.fixture(m.type("r, g, b"), 1).at(0, 0);
    java.lang.reflect.Field pending = ArtNetMapper.class.getDeclaredField("pending");
    pending.setAccessible(true);
    m.sample(new int[] {0xFFFF0000}, 1, 1, 1);
    assertTrue((boolean) pending.get(m));
    pending.set(m, false);
    m.sample(new int[] {0xFFFF0000}, 1, 1, 1);
    assertFalse((boolean) pending.get(m));
    m.sample(new int[] {0xFF00FF00}, 1, 1, 1);
    assertTrue((boolean) pending.get(m));
  }
}
