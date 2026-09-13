package com.chalkline;

import com.chalkline.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Web version of the ABETFI school timetable manager.
 *
 * Run locally:   mvn spring-boot:run
 * Then open:     http://localhost:8080
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class TimetableApplication {

    public static void main(String[] args) {
        SpringApplication.run(TimetableApplication.class, args);
    }
}
