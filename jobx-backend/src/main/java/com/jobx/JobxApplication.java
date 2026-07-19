package com.jobx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JobxApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobxApplication.class, args);
    }
}
