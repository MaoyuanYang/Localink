package com.localink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * M5-A 起启用定时任务（对账等后台作业）。
 */
@EnableScheduling
@SpringBootApplication
public class LocalinkApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocalinkApplication.class, args);
    }
}
