package com.boardinghousebot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    public DatabaseConfig() {
        log.info("DatabaseConfig initialized for SQLite datasource.");
    }
}
