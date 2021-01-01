# ruuvi-mqtt-data-publisher

This is a slightly altered version of [Scrin/RuuviCollector](https://github.com/Scrin/RuuviCollector) which can be used to publish data from [RuuviTags](https://ruuvi.com) to an MQTT broker. 

Personally I use this with various tags to publish data to [Home Assistant](https://www.home-assistant.io).

For more details and documentation, please refer to [Scrin/RuuviCollector](https://github.com/Scrin/RuuviCollector).

## Additional features

Few additional features that are available

### MQTT broker configuration

The following example shows how you can connect this application to an MQTT broker like [Eclipse Mosquitto](https://mosquitto.org)

```
mqtt.brokerUrls=tcp://localhost:1883
mqtt.username=some-username
mqtt.password=secret
mqtt.topic=/home/ruuvi
mqtt.clientId=ruuvi-mqtt-data-publisher
```

### Sensor update interval

If the update interval for a ruuvitag is too frequent you can specify a default interval as ISO-8601 duration format:

```
updateInterval=PT2M30S
```

This would give you update interval of 2 minutes and 30 seconds.

Or, if you want to define it per tag:

```
tag.D04AB59C588B.updateInterval=PT5S
```