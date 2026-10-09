package com.boardinghousebot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BoardingHouseBotApplication {

    private static final Logger log = LoggerFactory.getLogger(BoardingHouseBotApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(BoardingHouseBotApplication.class, args);
        log.info("Boarding House Bot Application started successfully.");
    }
}
