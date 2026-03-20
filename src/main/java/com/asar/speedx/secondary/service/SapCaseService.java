package com.asar.speedx.secondary.service;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class SapCaseService {

    private final WebClient webClient;

    public SapCaseService() {
        this.webClient = WebClient.create();
    }

}