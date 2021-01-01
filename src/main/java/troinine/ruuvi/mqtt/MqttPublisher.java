package troinine.ruuvi.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import fi.tkgwf.ruuvi.bean.EnhancedRuuviMeasurement;
import fi.tkgwf.ruuvi.config.Config;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class MqttPublisher {
    private static final Logger logger = LoggerFactory.getLogger(MqttPublisher.class);

    private static final String DUMMY_URL = "tcp://URL_OVERRIDDEN_BY_OPTIONS";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MqttConnectOptions connectionOptions;
    private MqttClient mqttClient;

    public MqttPublisher() {
        connectionOptions = new MqttConnectOptions();

        connectionOptions.setServerURIs(Config.getMqttBrokerUrls());
        connectionOptions.setUserName(Config.getMqttUsername());
        connectionOptions.setPassword(Config.getMqttPassword().toCharArray());
        connectionOptions.setAutomaticReconnect(true);

        logger.info("Broker URLs: {}", Arrays.toString(Config.getMqttBrokerUrls()));
        logger.info("Topics are:");

        if (!Config.getTagNames().isEmpty()) {
            Config.getTagNames()
                    .forEach(name -> logger.info("  - {}", resolveTopic(name)));
        } else {
            logger.info("  - {}", Config.getMqttTopic());
        }
    }

    public void publish(EnhancedRuuviMeasurement measurement) {
        synchronized (this) {
            if (mqttClient == null && !connect()) {
                return;
            }
        }

        try {
            if (logger.isDebugEnabled()) {
                String pretty = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(measurement);

                logger.debug("Sending MQTT message to topic {}:\n{}", Config.getMqttTopic(), pretty);
            }

            String messageJson = objectMapper.writeValueAsString(measurement);
            MqttMessage message = new MqttMessage(messageJson.getBytes(StandardCharsets.UTF_8));

            mqttClient.publish(resolveTopic(measurement.getName()), message);
        } catch (Exception e) {
            logger.warn("Failed to publish MQTT message", e);
        }
    }

    private String resolveTopic(String name) {
        String topic = Config.getMqttTopic();

        if (StringUtils.isNotBlank(name)) {
            topic = topic.concat("/").concat(name);
        }

        return topic;
    }

    public synchronized void disconnect() {
        if (mqttClient != null) {
            try {
                mqttClient.disconnectForcibly();
            } catch (MqttException mqttException) {
                // Intentionally ignored.
            } finally {
                mqttClient = null;
            }
        }
    }

    private boolean connect() {
        logger.info("Connecting to MQTT broker...");

        try {
            mqttClient = new MqttClient(
                    DUMMY_URL,
                    Config.getMqttClientId(),
                    null);

            mqttClient.connect(connectionOptions);
        } catch (MqttException e) {
            logger.warn("Failed to connect to MQTT Broker", e);
            mqttClient = null;

            return false;
        }

        logger.info("Successfully connected to MQTT Broker");

        return true;
    }
}
