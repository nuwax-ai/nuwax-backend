package com.xspaceagi.custompage.sdk.dto;

import lombok.Data;

@Data
public class ProxyConfigBackend {
    // http://192.168.1.34:3001/[xxx]
    private String backend;
    private int weight;
    private String apiKey;

    public ProxyConfigBackend() {
    }

    public ProxyConfigBackend(String backend, int weight) {
        this.backend = backend;
        this.weight = weight;
    }

    public ProxyConfigBackend(String backend, int weight, String apiKey) {
        this.backend = backend;
        this.weight = weight;
        this.apiKey = apiKey;
    }
}