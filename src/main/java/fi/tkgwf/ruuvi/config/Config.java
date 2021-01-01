package fi.tkgwf.ruuvi.config;

import fi.tkgwf.ruuvi.strategy.LimitingStrategy;
import fi.tkgwf.ruuvi.strategy.impl.DefaultDiscardingWithMotionSensitivityStrategy;
import fi.tkgwf.ruuvi.strategy.impl.DiscardUntilEnoughTimeHasElapsedStrategy;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

public abstract class Config {

    private static final Logger logger = LoggerFactory.getLogger(Config.class);
    private static final String RUUVI_COLLECTOR_PROPERTIES = "ruuvi-collector.properties";
    private static final String RUUVI_NAMES_PROPERTIES = "ruuvi-names.properties";

    private static final String DEFAULT_SCAN_COMMAND = "hcitool lescan --duplicates --passive";
    private static final String DEFAULT_DUMP_COMMAND = "hcidump --raw";

    private static final String[] DEFAULT_BROKER_URLS = { "tcp://localhost:1883" };
    private static final String DEFAULT_MQTT_TOPIC = "/ruuvi";

    private static long measurementUpdateLimit;
    private static String storageMethod;
    private static String storageValues;
    private static Predicate<String> filterMode;
    private static final Set<String> FILTER_MACS = new HashSet<>();
    private static final Map<String, String> TAG_NAMES = new HashMap<>();
    private static String[] scanCommand;
    private static String[] dumpCommand;
    private static Supplier<Long> timestampProvider;
    private static LimitingStrategy limitingStrategy;
    private static Double defaultWithMotionSensitivityStrategyThreshold;
    private static int defaultWithMotionSensitivityStrategyNumberOfPreviousMeasurementsToKeep;
    private static Map<String, TagProperties> tagProperties;
    private static Function<String, File> configFileFinder;
    private static String[] mqttBrokerUrls;
    private static String mqttUsername;
    private static String mqttPassword;
    private static String mqttClientId;
    private static String mqttTopic;
    private static Duration updateInterval;

    static {
        reload();
    }

    public static void reload() {
        reload(defaultConfigFileFinder());
    }

    public static void reload(final Function<String, File> configFileFinder) {
        Config.configFileFinder = configFileFinder;
        loadDefaults();
        readTagNames();
        readConfig();
    }

    private static void loadDefaults() {
        measurementUpdateLimit = 9900;
        storageMethod = "influxdb";
        storageValues = "extended";
        filterMode = (s) -> true;
        FILTER_MACS.clear();
        TAG_NAMES.clear();
        scanCommand = DEFAULT_SCAN_COMMAND.split(" ");
        dumpCommand = DEFAULT_DUMP_COMMAND.split(" ");
        timestampProvider = System::currentTimeMillis;
        limitingStrategy = new DiscardUntilEnoughTimeHasElapsedStrategy();
        defaultWithMotionSensitivityStrategyThreshold = 0.05;
        defaultWithMotionSensitivityStrategyNumberOfPreviousMeasurementsToKeep = 3;
        tagProperties = new HashMap<>();
        mqttBrokerUrls = DEFAULT_BROKER_URLS;
        mqttTopic = DEFAULT_MQTT_TOPIC;
        mqttUsername = "";
        mqttPassword = "";
        updateInterval = Duration.ZERO;
    }

    private static void readConfig() {
        try {
            final File configFile = configFileFinder.apply(RUUVI_COLLECTOR_PROPERTIES);
            if (configFile != null) {
                logger.debug("Config: " + configFile);
                Properties props = new Properties();
                props.load(new FileInputStream(configFile));
                readConfigFromProperties(props);
            }
        } catch (IOException ex) {
            logger.warn("Failed to read configuration, using default values...", ex);
        }
    }

    public static void readConfigFromProperties(final Properties props) {
        measurementUpdateLimit = parseLong(props, "measurementUpdateLimit", measurementUpdateLimit);
        storageMethod = props.getProperty("storage.method", storageMethod);
        storageValues = props.getProperty("storage.values", storageValues);
        filterMode = parseFilterMode(props);
        FILTER_MACS.addAll(parseFilterMacs(props));
        scanCommand = props.getProperty("command.scan", DEFAULT_SCAN_COMMAND).split(" ");
        dumpCommand = props.getProperty("command.dump", DEFAULT_DUMP_COMMAND).split(" ");
        limitingStrategy = parseLimitingStrategy(props);
        defaultWithMotionSensitivityStrategyThreshold = parseDouble(props, "limitingStrategy.defaultWithMotionSensitivity.threshold", defaultWithMotionSensitivityStrategyThreshold);
        defaultWithMotionSensitivityStrategyNumberOfPreviousMeasurementsToKeep = parseInteger(props, "limitingStrategy.defaultWithMotionSensitivity.numberOfMeasurementsToKeep", defaultWithMotionSensitivityStrategyNumberOfPreviousMeasurementsToKeep);
        tagProperties = parseTagProperties(props);
        mqttBrokerUrls = parseStringArray(props, "mqtt.brokerUrls", mqttBrokerUrls);
        mqttClientId = props.getProperty("mqtt.clientId", UUID.randomUUID().toString());
        mqttUsername = props.getProperty("mqtt.username", mqttUsername);
        mqttPassword = props.getProperty("mqtt.password", mqttPassword);
        mqttTopic = props.getProperty("mqtt.topic", mqttTopic);
        updateInterval = parseDuration(props, "updateInterval", updateInterval);
    }

    private static Duration parseDuration(Properties props, String key, Duration defaultInterval) {
        return Optional.ofNullable(props.getProperty(key))
                .map(Duration::parse)
                .orElse(defaultInterval);
    }

    private static String[] parseStringArray(Properties props, String key, String[] defaults) {
        return Optional.ofNullable(props.getProperty(key))
                .map(values -> values.split("\\s*,\\s*"))
                .orElse(defaults);
    }

    private static Map<String, TagProperties> parseTagProperties(final Properties props) {
        final Map<String, Map<String, String>> tagProps = props.entrySet().stream()
            .map(e -> Pair.of(String.valueOf(e.getKey()), String.valueOf(e.getValue())))
            .filter(p -> p.getLeft().startsWith("tag."))
            .collect(Collectors.groupingBy(extractMacAddressFromTagPropertyName(),
                toMap(extractKeyFromTagPropertyName(), Pair::getRight)));
        return tagProps.entrySet().stream().map(e -> {
            final TagProperties.Builder builder = TagProperties.builder(e.getKey());
            e.getValue().forEach(builder::add);
            return builder.build();
        }).collect(Collectors.toMap(TagProperties::getMac, t -> t));
    }

    private static Function<Pair<String, String>, String> extractKeyFromTagPropertyName() {
        return p -> p.getLeft().substring(17);
    }

    private static Function<Pair<String, String>, String> extractMacAddressFromTagPropertyName() {
        return p -> p.getLeft().substring(4, 16);
    }

    private static LimitingStrategy parseLimitingStrategy(final Properties props) {
        final String strategy = props.getProperty("limitingStrategy");
        if (strategy != null) {
            if ("defaultWithMotionSensitivity".equals(strategy)) {
                return new DefaultDiscardingWithMotionSensitivityStrategy();
            }
        }
        return new DiscardUntilEnoughTimeHasElapsedStrategy();
    }

    private static Collection<? extends String> parseFilterMacs(final Properties props) {
        return Optional.ofNullable(props.getProperty("filter.macs"))
            .map(value -> Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> s.length() == 12)
                .map(String::toUpperCase).collect(toSet()))
            .orElse(Collections.emptySet());
    }

    private static Predicate<String> parseFilterMode(final Properties props) {
        final String filter = props.getProperty("filter.mode");
        if (filter != null) {
            switch (filter) {
                case "blacklist":
                    return (s) -> !FILTER_MACS.contains(s);
                case "whitelist":
                    return FILTER_MACS::contains;
                case "named":
                    if (TAG_NAMES.isEmpty()) {
                        throw new IllegalStateException(
                        "You have set filter.mode=named but left ruuvi-names.properties empty. " +
                        "Please select a different filter.mode value or populate ruuvi-names.properties.");
                    }
                    return TAG_NAMES.keySet()::contains;
            }
        }
        return filterMode;
    }

    private static long parseLong(final Properties props, final String key, final long defaultValue) {
        return parseNumber(props, key, defaultValue, Long::parseLong);
    }

    private static int parseInteger(final Properties props, final String key, final int defaultValue) {
        return parseNumber(props, key, defaultValue, Integer::parseInt);
    }

    private static double parseDouble(final Properties props, final String key, final double defaultValue) {
        return parseNumber(props, key, defaultValue, Double::parseDouble);
    }

    private static <N extends Number> N parseNumber(final Properties props, final String key, final N defaultValue, final Function<String, N> parser) {
        final String value = props.getProperty(key);
        try {
            return Optional.ofNullable(value).map(parser).orElse(defaultValue);
        } catch (final NumberFormatException ex) {
            logger.warn("Malformed number format for " + key + ": '" + value + '\'');
            return defaultValue;
        }
    }

    private static boolean parseBoolean(final Properties props, final String key, final boolean defaultValue) {
        return Optional.ofNullable(props.getProperty(key)).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    private static Function<String, File> defaultConfigFileFinder() {
        return propertiesFileName -> {
            try {
                final File jarLocation = new File(Config.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()).getParentFile();
                Optional<File> configFile = findConfigFile(propertiesFileName, jarLocation);
                if (!configFile.isPresent()) {
                    // look for config files in the parent directory if none found in the current directory, this is useful during development when
                    // RuuviCollector can be run from maven target directory directly while the config file sits in the project root
                    final File parentFile = jarLocation.getParentFile();
                    configFile = findConfigFile(propertiesFileName, parentFile);
                }
                return configFile.orElse(null);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private static Optional<File> findConfigFile(String propertiesFileName, File parentFile) {
        return Optional.ofNullable(parentFile.listFiles(f -> f.isFile() && f.getName().equals(propertiesFileName)))
            .filter(configFiles -> configFiles.length > 0)
            .map(configFiles -> configFiles[0]);
    }

    private static void readTagNames() {
        try {
            final File configFile = configFileFinder.apply(RUUVI_NAMES_PROPERTIES);
            if (configFile != null) {
                logger.debug("Tag names: " + configFile);
                Properties props = new Properties();
                props.load(new FileInputStream(configFile));
                Enumeration<?> e = props.propertyNames();
                while (e.hasMoreElements()) {
                    String key = StringUtils.trimToEmpty((String) e.nextElement()).toUpperCase();
                    String value = StringUtils.trimToEmpty(props.getProperty(key));
                    if (key.length() == 12 && value.length() > 0) {
                        TAG_NAMES.put(key, value);
                    }
                }
            }
        } catch (IOException ex) {
            logger.warn("Failed to read tag names", ex);
        }
    }

    public static long getMeasurementUpdateLimit() {
        return measurementUpdateLimit;
    }

    public static boolean isAllowedMAC(String mac) {
        return mac != null && filterMode.test(mac);
    }

    public static String[] getScanCommand() {
        return scanCommand;
    }

    public static String[] getDumpCommand() {
        return dumpCommand;
    }

    public static String getTagName(String mac) {
        return TAG_NAMES.get(mac);
    }

    public static Supplier<Long> getTimestampProvider() {
        return timestampProvider;
    }

    public static LimitingStrategy getLimitingStrategy() {
        return limitingStrategy;
    }

    public static LimitingStrategy getLimitingStrategy(String mac) {
        return Optional.ofNullable(tagProperties.get(mac))
            .map(TagProperties::getLimitingStrategy)
            .orElse(null);
    }

    public static Double getDefaultWithMotionSensitivityStrategyThreshold() {
        return defaultWithMotionSensitivityStrategyThreshold;
    }

    public static int getDefaultWithMotionSensitivityStrategyNumberOfPreviousMeasurementsToKeep() {
        return defaultWithMotionSensitivityStrategyNumberOfPreviousMeasurementsToKeep;
    }

    public static String[] getMqttBrokerUrls() {
        return mqttBrokerUrls;
    }

    public static String getMqttUsername() {
        return mqttUsername;
    }

    public static String getMqttPassword() {
        return mqttPassword;
    }

    public static String getMqttClientId() {
        return mqttClientId;
    }

    public static String getMqttTopic() {
        return mqttTopic;
    }

    public static List<String> getTagNames() {
        return new ArrayList<>(TAG_NAMES.values());
    }

    public static Duration getUpdateInterval(String mac) {
        return Optional.ofNullable(tagProperties.get(mac))
                .map(TagProperties::getUpdateInterval)
                .orElse(updateInterval);
    }
}
