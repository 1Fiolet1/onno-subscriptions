package ru.seleznev.onnosubscriptions.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class BusinessClockConfiguration {
    @Bean
    public Clock businessClock(@Value("${subscriptions.time-zone}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
