package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.spec.AlgorithmParameterSpec;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@SuppressWarnings("unchecked")
public class SubscriptionService {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public SubscriptionService(RestTemplateBuilder builder, ObjectMapper objectMapper, AppProperties appProperties) {
        this.restTemplate = builder
                .defaultHeader(HttpHeaders.ACCEPT, Constants.ACCEPT)
                .defaultHeader(HttpHeaders.USER_AGENT, Constants.USER_AGENT)
                .build();
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    public Map<String, Object> subscription(int id) {
        String apiUrl = "";
        if (id > 0) {
            apiUrl = appProperties.getConfigUrl();
        }

        return subscription(apiUrl);
    }

    public Map<String, Object> subscription(String apiUrl) {
        String configKey = null;
        String configUrl = apiUrl;
        String pk = ";pk;";
        if (apiUrl != null && apiUrl.contains(pk)) {
            String[] a = apiUrl.split(pk);
            configUrl = a[0];
            configKey = a[1];
        }
        if (StringUtils.isNotBlank(configUrl) && !configUrl.startsWith("http")) {
            configUrl = "http://" + configUrl;
        }

        String json = loadConfigJson(configUrl);
        Map<String, Object> config = convertResult(json, configKey);

        addSite(config);
        addRules(config);

        return config;
    }

    private static void addSite(Map<String, Object> config) {
        Map<String, Object> site = buildSite();
        List<Map<String, Object>> sites = (List<Map<String, Object>>) config.get("sites");
        sites.add(0, site);
    }

    private static Map<String, Object> buildSite() {
        Map<String, Object> site = new HashMap<>();
        ServletUriComponentsBuilder builder = ServletUriComponentsBuilder.fromCurrentRequestUri();
        builder.replacePath("/vod");
        site.put("key", "AListAPI");
        site.put("name", "AList┃转发");
        site.put("type", 1);
        site.put("api", builder.build().toUriString());
        site.put("searchable", 1);
        site.put("quickSearch", 1);
        site.put("filterable", 1);
        return site;
    }

    private static void addRules(Map<String, Object> config) {
        List<Map<String, Object>> rules = (List<Map<String, Object>>) config.get("rules");
        if (rules == null) {
            rules = new ArrayList<>();
            config.put("rules", rules);
        }
        Map<String, Object> rule = new HashMap<>();
        rule.put("host", "pdsapi.aliyundrive.com");
        rule.put("rule", Collections.singletonList("/redirect"));
        rules.add(rule);

        rule = new HashMap<>();
        rule.put("host", "*");
        rule.put("rule", Collections.singletonList("http((?!http).){12,}?\\\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|ape|flac|wav|wma|m4a)\\\\?.*"));
        rules.add(rule);

        rule = new HashMap<>();
        rule.put("host", "*");
        rule.put("rule", Collections.singletonList("http((?!http).){12,}\\\\.(m3u8|mp4|flv|avi|mkv|rm|wmv|mpg|ape|flac|wav|wma|m4a)"));
        rules.add(rule);
    }

    private String loadConfigJson(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        try {
            log.info("load json from {}", url);
            return restTemplate.getForObject(url, String.class);
        } catch (Exception e) {
            log.warn("load config json failed", e);
            return null;
        }
    }

    public Map<String, Object> convertResult(String json, String configKey) {
        Map<String, Object> map = new HashMap<>();
        map.put("sites", new ArrayList<>());
        map.put("rules", new ArrayList<>());
        if (json == null || json.isEmpty()) {
            return map;
        }

        String content = json;
        try {
            try {
                return objectMapper.readValue(json, Map.class);
            } catch (Exception e) {
                // ignore
            }

            Pattern pattern = Pattern.compile("[A-Za-z0]{8}\\*\\*");
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                content = content.substring(content.indexOf(matcher.group()) + 10);
                content = new String(Base64.getDecoder().decode(content));
            }

            if (content.startsWith("2423")) {
                String data = content.substring(content.indexOf("2324") + 4, content.length() - 26);
                content = new String(toBytes(content)).toLowerCase();
                String key = rightPadding(content.substring(content.indexOf("$#") + 2, content.indexOf("#$")), "0", 16);
                String iv = rightPadding(content.substring(content.length() - 13), "0", 16);
                json = CBC(data, key, iv);
            } else if (configKey != null) {
                try {
                    return objectMapper.readValue(json, Map.class);
                } catch (Exception e) {
                    // ignore
                }
                json = ECB(content, configKey);
            } else {
                json = content;
            }

            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("", e);
            return map;
        }
    }

    public static String rightPadding(String key, String replace, int Length) {
        String strReturn;
        int curLength = key.trim().length();
        if (curLength > Length) {
            strReturn = key.trim().substring(0, Length);
        } else if (curLength == Length) {
            strReturn = key.trim();
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < (Length - curLength); i++) {
                sb.append(replace);
            }
            strReturn = key.trim() + sb;
        }
        return strReturn;
    }

    public static String ECB(String data, String key) {
        try {
            key = rightPadding(key, "0", 16);
            byte[] data2 = toBytes(data);
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS7Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            return new String(cipher.doFinal(data2));
        } catch (Exception e) {
            log.warn("", e);
        }
        return null;
    }

    public static String CBC(String data, String key, String iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "AES");
            AlgorithmParameterSpec paramSpec = new IvParameterSpec(iv.getBytes());
            cipher.init(Cipher.DECRYPT_MODE, keySpec, paramSpec);
            return new String(cipher.doFinal(toBytes(data)));
        } catch (Exception e) {
            log.warn("", e);
        }
        return null;
    }

    private static byte[] toBytes(String src) {
        int l = src.length() / 2;
        byte[] ret = new byte[l];
        for (int i = 0; i < l; i++) {
            ret[i] = Integer.valueOf(src.substring(i * 2, i * 2 + 2), 16).byteValue();
        }
        return ret;
    }

}
