package com.openforge.drawing;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.openforge") // starter 的公共装配经包扫描生效
@MapperScan("com.openforge.drawing.mapper")
public class DrawingApplication {

    public static void main(String[] args) {
        SpringApplication.run(DrawingApplication.class, args);
    }
}
