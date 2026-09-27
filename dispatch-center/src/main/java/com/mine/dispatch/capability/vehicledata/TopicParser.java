package com.mine.dispatch.capability.vehicledata;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TopicParser {
    private static final Pattern PATTERN = Pattern.compile(
            "^mine/sim/([^/]+)/state/(map|vehicle/(-?\\d+)|shovel/(-?\\d+))$");

    public ParsedTopic parse(String topic) {
        Matcher matcher = PATTERN.matcher(topic);
        if (!matcher.matches()) throw new IllegalArgumentException("unsupported MQTT topic");
        String simulationId = matcher.group(1);
        String suffix = matcher.group(2);
        if (suffix.equals("map")) {
            return new ParsedTopic(simulationId, ParsedTopic.Type.MAP, null);
        }
        if (suffix.startsWith("vehicle/")) {
            return new ParsedTopic(simulationId, ParsedTopic.Type.VEHICLE,
                    Integer.valueOf(matcher.group(3)));
        }
        return new ParsedTopic(simulationId, ParsedTopic.Type.SHOVEL,
                Integer.valueOf(matcher.group(4)));
    }
}
