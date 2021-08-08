package fi.tkgwf.ruuvi;

import fi.tkgwf.ruuvi.config.Config;
import fi.tkgwf.ruuvi.config.ConfigTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static fi.tkgwf.ruuvi.TestFixture.RSSI_BYTE;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @BeforeEach
    void resetConfigBefore() {
        Config.reload(ConfigTest.configTestFileFinder());
    }

    @AfterAll
    static void restoreClock() {
        Config.reload(ConfigTest.configTestFileFinder());
        TestFixture.setClockToMilliseconds(System::currentTimeMillis);
    }

    @Test
    void integrationTest() {
        // Setup the test. Use two devices and change one variable for each hcidump line so that the messages
        // can be told apart at the end.

        final String hcidataOfDevice1 = TestFixture.getDataFormat3Message();
        final String hcidata2OfDevice2 = TestFixture.getDataFormat3Message()
            .replace("AA", "BB"); // Changing the MAC address

        final Main main = new Main();
        final BufferedReader reader = new BufferedReader(new StringReader(
            "Ignorable garbage at the start" + "\n"
                + hcidataOfDevice1.replace(RSSI_BYTE, "01") + "\n"
                + hcidataOfDevice1.replace(RSSI_BYTE, "02") + "\n"
                + hcidataOfDevice1.replace(RSSI_BYTE, "03") + "\n"
                + hcidata2OfDevice2.replace(RSSI_BYTE, "04") + "\n"
                + hcidata2OfDevice2.replace(RSSI_BYTE, "05") + "\n"
        ));

        // The following are the timestamps on which the hcidump lines above will be read.
        // By default (see Config.getMeasurementUpdateLimit()) a measurement is discarded
        // if it arrives less than 9900 milliseconds after the previous measurement from
        // the same device.
        setClockToMilliseconds(0L, 5000L, 10000L, 11000L, 12000L, 99999L);

        // Enough with the setup, run the process:

        final boolean runResult = false; // main.run(reader);
        assertTrue(runResult);
    }

    private void setClockToMilliseconds(final Long... millis) {
        TestFixture.setClockToMilliseconds(new FixedInstantsProvider(Arrays.asList(millis)));
    }

    /**
     * A timestamp supplier whose readings can be pre-programmed.
     */
    static final class FixedInstantsProvider implements Supplier<Long> {
        private final List<Long> instants;
        private int readCount = 0;

        FixedInstantsProvider(List<Long> fixedInstants) {
            this.instants = fixedInstants;
        }

        @Override
        public Long get() {
            final long millis = instants.get(readCount);
            readCount++;
            return millis;
        }
    }
}
