package com.ledger.txprocessor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TransactionProcessorApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransactionProcessorApplication.class, args);
    }
}
