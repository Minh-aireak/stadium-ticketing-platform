package com.aireak.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(List<String> trustedProxies) {

    public GatewayProperties {
        if (trustedProxies == null) {
            trustedProxies = List.of("127.0.0.1", "0:0:0:0:0:0:0:1", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");
        }
    }
}
