package com.openforge.change;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.openforge")
@MapperScan("com.openforge.change.mapper")
public class ChangeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChangeApplication.class, args);
    }
}
