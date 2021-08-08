package troinine.ruuvi.hci;

import fi.tkgwf.ruuvi.config.Config;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class HciProcessHandler {
    private static final Logger logger = LoggerFactory.getLogger(HciProcessHandler.class);
    private static final long CHECK_DATA_DELAY = 5000L;
    private Process hciScanProcess;
    private Process hciDumpProcess;
    private BufferedReader reader;
    private Instant lastLineRead = Instant.now();

    private Thread processMonitor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public void start() {
        if (running.getAndSet(true)) {
            throw new IllegalStateException();
        }

        try {
            if (shouldScan()) {
                startScanning();
                startProcessMonitor();
            } else {
                logger.debug("Skipping scan command, scan command is blank.");
            }

            startDumpping();
        } catch (IOException e) {
            stop();

            throw new RuntimeException("Failed to start hci processes", e);
        }

        reader = new BufferedReader(new InputStreamReader(hciDumpProcess.getInputStream()));
    }

    private void startProcessMonitor() {
        processMonitor = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(CHECK_DATA_DELAY);

                    if (!isRunning()) {
                        return;
                    }

                    if (lastLineRead.isBefore(Instant.now().minusSeconds(Config.getScanRestartIfNoData()))) {
                        logger.info("No BLE data received in {} seconds", Config.getScanRestartIfNoData());

                        restartScanning();
                    }
                }
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting the next check", e);
            }
        });

        processMonitor.start();
    }

    public String readLine() throws IOException {
        if (!running.get()) {
            throw new IllegalStateException();
        }

        String line = reader.readLine();
        lastLineRead = Instant.now();

        return line;
    }

    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        if (processMonitor != null) {
            processMonitor.interrupt();
            processMonitor = null;
        }

        if (hciScanProcess != null) {
            logger.info("Stopping scan process...");
            hciScanProcess.destroy();
            hciScanProcess = null;
        }

        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                // Intentionally ignored
            }

            reader = null;
        }

        if (hciDumpProcess != null) {
            logger.info("Stopping dump process...");
            hciDumpProcess.destroy();
            hciDumpProcess = null;
        }
    }

    private void startDumpping() throws IOException {
        logger.debug("Starting dump with: " + Arrays.toString(Config.getDumpCommand()));

        hciDumpProcess = new ProcessBuilder(Config.getDumpCommand()).start();

        logger.info("Started dump process");
    }

    private void startScanning() throws IOException {
        logger.debug("Starting scan with: " + Arrays.toString(Config.getScanCommand()));

        hciScanProcess = new ProcessBuilder(Config.getScanCommand()).start();

        logger.info("Started scan process");
    }

    private void restartScanning() {
        try {
            if (hciScanProcess != null) {
                hciScanProcess.destroy();
                hciScanProcess = null;
            }
        } catch (Exception e) {
            // Intentionally ignored.
        }

        logger.info("Restarting scanning in {} secs", Config.getScanRestartDelaySecs());

        try {
            Thread.sleep(Config.getScanRestartDelaySecs() * 1000L);

            startScanning();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to restart scanning", e);
        }
    }

    private boolean shouldScan() {
        String[] scan = Config.getScanCommand();

        return scan.length > 0 && StringUtils.isNotBlank(scan[0]);
    }

    private boolean isRunning() {
        return running.get();
    }
}
