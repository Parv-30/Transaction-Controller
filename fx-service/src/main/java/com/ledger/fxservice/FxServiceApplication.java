package com.ledger.fxservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FxServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FxServiceApplication.class, args);
    }
}
