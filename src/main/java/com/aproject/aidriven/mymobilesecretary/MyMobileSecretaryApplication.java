package com.aproject.aidriven.mymobilesecretary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MyMobileSecretaryApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyMobileSecretaryApplication.class, args);
    }

}
