package com.gzu.shuttle;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.gzu.shuttle.mapper")
@EnableScheduling
public class ShuttleApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShuttleApplication.class, args);
    }
}