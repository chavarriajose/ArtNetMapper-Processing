package artnetmapper;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The version lives in library.properties; ArtNetMapper.VERSION must match it. */
class VersionTest {

  @Test
  void versionMatchesLibraryProperties() {
    String fromBuild = System.getProperty("artnetmapper.version");
    assertNotNull(fromBuild, "run through gradle test");
    assertEquals(fromBuild, ArtNetMapper.VERSION);
  }
}
