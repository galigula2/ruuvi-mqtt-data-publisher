package troinine.ruuvi.hci;

import fi.tkgwf.ruuvi.config.Config;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class HciProcessHandler {
    private static final Logger logger = LoggerFactory.getLogger(HciProcessHandler.class);
    private Process hciScanProcess;
    private Process hciDumpProcess;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public InputStream start() {
        if (running.getAndSet(true)) {
            throw new IllegalStateException();
        }

        try {
            if (shouldScan()) {
                startScanning();
            } else {
                logger.debug("Skipping scan command, scan command is blank.");
            }

            logger.debug("Starting dump with: " + Arrays.toString(Config.getDumpCommand()));

            hciDumpProcess = new ProcessBuilder(Config.getDumpCommand()).start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start hci processes", e);
        }

        return hciDumpProcess.getInputStream();
    }

    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        if (hciScanProcess != null) {
            logger.info("Stopping scan process...");
            hciScanProcess.destroy();
            hciScanProcess = null;
        }

        if (hciDumpProcess != null) {
            logger.info("Stopping dump process...");
            hciDumpProcess.destroy();
            hciDumpProcess = null;
        }
    }

    private void startScanning() throws IOException {
        hciScanProcess = new ProcessBuilder(Config.getScanCommand()).start();

        logger.info("Started scan process");

        new Thread(() -> {
            try {
                hciScanProcess.waitFor();
                int exitValue = hciScanProcess.exitValue();

                if (shouldRestartScanning(exitValue)) {
                    logger.warn("Scan process was unexpectedly stopped, exit code {}. Restarting after {} seconds",
                            exitValue,
                            Config.getScanRestartSecs());

                    Thread.sleep(Config.getScanRestartSecs() * 1000L);
                    startScanning();
                }
            } catch (InterruptedException | IOException e) {
                // Interrupted so just ignore as we are exiting
            }

        }).start();

        logger.debug("Starting scan with: " + Arrays.toString(Config.getScanCommand()));
    }

    private boolean shouldRestartScanning(int exitValue) {
        return (running.get() &&
                exitValue != 0 &&
                exitValue != 143); // 143 is SIGTERM
    }

    private boolean shouldScan() {
        String[] scan = Config.getScanCommand();

        return scan.length > 0 && StringUtils.isNotBlank(scan[0]);
    }
}
