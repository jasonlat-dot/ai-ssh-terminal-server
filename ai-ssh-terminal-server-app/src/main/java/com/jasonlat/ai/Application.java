
package com.jasonlat.ai;

import com.jasonlat.ai.domain.agent.service.amory.matter.mcp.server.MyTestMcpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@Configurable
@EnableScheduling
@SpringBootApplication
//@ComponentScan(basePackages = {"com.jasonlat.ai"})
public class Application {

    public static void main(String[] args){
        SpringApplication.run(Application.class);
        log.info("项目开始启动...");
    }

    /**
     * 创建工具回调提供者
     * @param testMcpService 测试 mcp 服务
     * @return 工具回调提供者
     */
    @Bean("myToolCallbackProvider")
    public ToolCallbackProvider toolCallbackProvider(MyTestMcpService testMcpService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(testMcpService) // 支持数组传参
                .build();
    }

}
