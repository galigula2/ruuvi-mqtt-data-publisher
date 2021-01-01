package fi.tkgwf.ruuvi.config;

import fi.tkgwf.ruuvi.strategy.impl.DefaultDiscardingWithMotionSensitivityStrategy;
import fi.tkgwf.ruuvi.strategy.impl.DiscardUntilEnoughTimeHasElapsedStrategy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConfigTest {

    public static Function<String, File> configTestFileFinder() {
        return propertiesFileName -> Optional.ofNullable(Config.class.getResource(String.format("/%s", propertiesFileName)))
            .map(url -> {
                try {
                    return url.toURI();
                } catch (final URISyntaxException e) {
                    throw new RuntimeException(e);
                }
            })
            .map(File::new)
            .orElse(null);
    }

    @BeforeEach
    void resetConfigBefore() {
        Config.reload(configTestFileFinder());
    }

    @AfterAll
    static void resetConfigAfter() {
        Config.reload(configTestFileFinder());
    }
    @Test
    void testDefaultLongValue() {
        assertEquals(9900, Config.getMeasurementUpdateLimit());
    }

    @Test
    void testOverriddenDoubleValues() {
        assertEquals(Double.valueOf(0.06d), Config.getDefaultWithMotionSensitivityStrategyThreshold());
    }

    @Test
    void testOverriddenMacFilterList() {
        assertFalse(Config.isAllowedMAC(null));
        assertFalse(Config.isAllowedMAC("ABCDEF012345"));
        assertFalse(Config.isAllowedMAC("F1E2D3C4B5A6"));
        assertTrue(Config.isAllowedMAC("123000000456"));
    }

    @Test
    void testNameThatCanBeFound() {
        assertEquals("Some named tag", Config.getTagName("AB12CD34EF56"));
    }

    @Test
    void testNameThatCanNotBeFound() {
        assertNull(Config.getTagName("123456789012"));
    }

    @Test
    void testLimitingStrategyPerMac() {
        assertTrue(Config.getLimitingStrategy("ABCDEF012345") instanceof DiscardUntilEnoughTimeHasElapsedStrategy);
        assertTrue(Config.getLimitingStrategy("F1E2D3C4B5A6") instanceof DefaultDiscardingWithMotionSensitivityStrategy);

        assertNull(Config.getLimitingStrategy("unknown should get null"));
    }

    @Test
    void testparseFilterMode() {

        assertTrue(Config.isAllowedMAC("AB12CD34EF56"));
        assertTrue(Config.isAllowedMAC("XX12CD34EF56"));
        assertTrue(Config.isAllowedMAC("ABCDEFG"));
        assertFalse(Config.isAllowedMAC(null));

         //Change to named
        final Properties properties = new Properties();
        properties.put("filter.mode", "named");
        Config.readConfigFromProperties(properties);
        assertTrue(Config.isAllowedMAC("AB12CD34EF56"));
        assertFalse(Config.isAllowedMAC("XX12CD34EF56"));
        assertFalse(Config.isAllowedMAC("ABCDEFG"));
        assertFalse(Config.isAllowedMAC(null));
    }
}
