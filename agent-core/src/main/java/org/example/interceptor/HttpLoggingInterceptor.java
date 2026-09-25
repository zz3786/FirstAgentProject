package org.example.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class HttpLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(HttpLoggingInterceptor.class);

    @Override
    public ClientHttpResponse intercept(HttpRequest request,
                                        byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {

        log.info("========== HTTP 请求 ==========");
        log.info("URL    : {} {}", request.getMethod(), request.getURI());
        log.info("Headers: {}", request.getHeaders());

        String bodyStr = new String(body, StandardCharsets.UTF_8);
        String masked = bodyStr.replaceAll(
                "(sk-[a-zA-Z0-9._-]{6})[a-zA-Z0-9._-]+", "$1****");
        log.info("Body   : {}", masked);

        ClientHttpResponse response = execution.execute(request, body);

        log.info("Response Status: {}", response.getStatusCode());
        log.info("===============================");
        return response;
    }
}