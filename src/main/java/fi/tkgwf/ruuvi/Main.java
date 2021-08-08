package fi.tkgwf.ruuvi;

import fi.tkgwf.ruuvi.bean.HCIData;
import fi.tkgwf.ruuvi.config.Config;
import fi.tkgwf.ruuvi.handler.BeaconHandler;
import fi.tkgwf.ruuvi.utils.HCIParser;
import fi.tkgwf.ruuvi.utils.MeasurementValueCalculator;
import fi.tkgwf.ruuvi.utils.Utils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import troinine.ruuvi.hci.HciProcessHandler;
import troinine.ruuvi.mqtt.MqttPublisher;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private final BeaconHandler beaconHandler = new BeaconHandler();
    private final MqttPublisher mqttPublisher = new MqttPublisher();
    private final HciProcessHandler hciProcessHandler = new HciProcessHandler();

    public static void main(String[] args) {
        Main m = new Main();

        if (!m.run()) {
            logger.info("Unclean exit");
            System.exit(1);
        }

        logger.info("Clean exit");
    }

    /**
     * Run the collector.
     *
     * @return true if the run ends gracefully, false in case of severe errors
     */
    public boolean run() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));

        try {
            startHciListeners();

            logger.info("BLE listener started successfully, waiting for data...");
            logger.info("If you don't get any data, check that you are able to run 'hcitool lescan' and 'hcidump --raw' without issues");

            return read();
        } catch (IOException ex) {
            logger.error("Failed to start hci processes", ex);
            return false;
        }
    }

    private void cleanup() {
        mqttPublisher.disconnect();
        hciProcessHandler.stop();
    }

    private void startHciListeners() throws IOException {
        hciProcessHandler.start();
    }

    boolean read() {
        HCIParser parser = new HCIParser();
        Map<String, Instant> lastUpdates = new HashMap<>();
        boolean dataReceived = false;
        boolean healthy = false;
        try {
            String line, latestMAC = null;
            while ((line = hciProcessHandler.readLine()) != null) {
                if (line.contains("device: disconnected")) {
                    logger.error(line + ": Either the bluetooth device was externally disabled or physically disconnected");
                    healthy = false;
                }
                if (line.contains("No such device")) {
                    logger.error(line + ": Check that your bluetooth adapter is enabled and working properly");
                    healthy = false;
                }
                if (!dataReceived) {
                    if (line.startsWith("> ")) {
                        logger.info("Successfully reading data from hcidump");
                        dataReceived = true;
                        healthy = true;
                    } else {
                        continue; // skip the unnecessary garbage at beginning containing hcidump version and other junk print
                    }
                }
                try {
                    //Read in MAC address from first line
                    if (Utils.hasMacAddress(line)) {
                        latestMAC = Utils.getMacFromLine(line);
                    }
                    //Apply Mac Address Filtering
                    if (Config.isAllowedMAC(latestMAC)) {
                        HCIData hciData = parser.readLine(line);
                        if (hciData != null) {
                            if (shouldUpdate(hciData, lastUpdates)) {
                                beaconHandler.handle(hciData)
                                        .map(MeasurementValueCalculator::calculateAllValues)
                                        .ifPresent(mqttPublisher::publish);
                            }
                            latestMAC = null; // "reset" the mac to null to avoid misleading MAC addresses when an error happens *after* successfully reading a full packet
                            healthy = true;
                        }
                    }
                } catch (Exception ex) {
                    if (latestMAC != null) {
                        logger.warn("Uncaught exception while handling measurements from MAC address \"" + latestMAC + "\", if this repeats and this is not a Ruuvitag, try blacklisting it", ex);
                    } else {
                        logger.warn("Uncaught exception while handling measurements, this is an unexpected event. Please report this to https://github.com/Scrin/RuuviCollector/issues and include this log", ex);
                    }
                    logger.debug("Offending line: " + line);
                }
            }
        } catch (IOException ex) {
            logger.error("Uncaught exception while reading measurements", ex);
            return false;
        }
        return healthy;
    }

    private boolean shouldUpdate(HCIData hciData, Map<String, Instant> lastUpdates) {
        String mac = hciData.mac;
        Duration updateInterval = Config.getUpdateInterval(mac);
        Instant lastUpdated = lastUpdates.get(mac);
        Instant now = Instant.now();

        if (lastUpdated == null) {
            lastUpdates.put(mac, now);
            return true;
        }

        Duration diff = Duration.between(lastUpdated, now);

        boolean shouldUpdate = updateInterval.minus(diff).isNegative();
        if (shouldUpdate) {
            lastUpdates.put(mac, now);
        }

        return shouldUpdate;
    }
}
