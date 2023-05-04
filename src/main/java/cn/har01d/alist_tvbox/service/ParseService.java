package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.util.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.HttpURLConnection;
import java.util.Objects;

@Slf4j
@Service
public class ParseService {
    private final RestTemplate restTemplate;

    public ParseService(RestTemplateBuilder builder) {
        restTemplate = builder
                .defaultHeader(HttpHeaders.ACCEPT, "*/*")
                .defaultHeader(HttpHeaders.USER_AGENT, Constants.USER_AGENT)
                .requestFactory(() -> new SimpleClientHttpRequestFactory() {
                    @Override
                    protected void prepareConnection(HttpURLConnection connection, String httpMethod) {
                        connection.setInstanceFollowRedirects(false);
                    }
                }).build();
    }

    public String parse(String url, ServletUriComponentsBuilder builder) {
        log.info("parse url: {}", url);
        String result = url;

        if (url.contains("/redirect")) {
            ResponseEntity<Void> response = restTemplate.getForEntity(url, Void.class);
            String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
            if (location != null) {
                result = location;
            }
        }
        if(url.contains("192.168.2.101") && Objects.equals(builder.build().getHost(), "hhzhome.accesscam.org")){
            result = result.replace("192.168.2.101:5244","hhzhome.accesscam.org:35244");
        }

        log.info("result: {}", result);
        return result;
    }
}
