package com.asar.speedx.secondary;

import com.asar.speedx.secondary.config.SapConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SapConfig.class)
public class SecondaryTeamAssignerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecondaryTeamAssignerApplication.class, args);
    }

}