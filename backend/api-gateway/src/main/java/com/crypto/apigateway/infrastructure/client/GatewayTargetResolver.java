package com.crypto.apigateway.infrastructure.client;

import com.crypto.apigateway.infrastructure.config.RouteTable;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class GatewayTargetResolver {

    private final LoadBalancerClient loadBalancer;

    public GatewayTargetResolver(LoadBalancerClient loadBalancer) {
        this.loadBalancer = loadBalancer;
    }

    public URI resolve(String rawPath, String queryString) {
        String serviceId = RouteTable.serviceId(rawPath);
        ServiceInstance instance = loadBalancer.choose(serviceId);
        if (instance == null) {
            throw new IllegalStateException("SERVICE_UNAVAILABLE:" + serviceId);
        }
        String suffix = (queryString == null || queryString.isEmpty()) ? rawPath : rawPath + "?" + queryString;
        return loadBalancer.reconstructURI(instance, URI.create("http://" + serviceId + suffix));
    }
}

