package com.interview.echo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class EchoController {

    @GetMapping({"/echo/test", "/echo-auth/test"})
    public Map<String, Object> get() {
        return buildResponse();
    }

    @PostMapping({"/echo/test", "/echo-auth/test"})
    public Map<String, Object> post() {
        return buildResponse();
    }

    private Map<String, Object> buildResponse() {
        return Map.of("status", 200, "message", "ok");
    }
}
