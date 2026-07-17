package com.aproject.internal.aidispatcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiDispatcherApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiDispatcherApplication.class, args);
    }
}
