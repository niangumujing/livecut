package com.ngmj;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication(scanBasePackages = {"com.ngmj"})
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class);
    }
}