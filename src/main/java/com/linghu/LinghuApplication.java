package com.linghu;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 灵狐·优库近选 - 后端启动类
 * 三端统一入口：消费者(role=0) / 仓主(role=1) / 品牌方(role=2)
 */
@SpringBootApplication
@MapperScan("com.linghu.mapper")
@EnableAsync
public class LinghuApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinghuApplication.class, args);
        System.out.println("==============================================");
        System.out.println("  灵狐·优库近选 后端服务启动成功！");
        System.out.println("  API: http://localhost:8080");
        System.out.println("  测试账号: consumer/warehouse/brand (密码: 123456)");
        System.out.println("==============================================");
    }
}
