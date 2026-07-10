package com.aproject.aidriven.mymobilesecretary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MyMobileSecretaryApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(MyMobileSecretaryApplication.class);
        // Windows toast 通知需要 AWT SystemTray;Spring Boot 預設 headless=true 會擋掉。
        // Linux 伺服器上仍安全:toast sender 有 @ConditionalOnProperty,預設不啟用。
        app.setHeadless(false);
        app.run(args);
    }

}
