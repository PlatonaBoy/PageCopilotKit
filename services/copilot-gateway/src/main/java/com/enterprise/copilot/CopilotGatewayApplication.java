package com.enterprise.copilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CopilotGatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(CopilotGatewayApplication.class, args);
  }
}
