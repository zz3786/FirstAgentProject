package org.example.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

@SpringBootTest
class EmbeddingDebugTest {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Test
    void checkConfig() {
        System.out.println("=== EmbeddingModel 信息 ===");
        System.out.println("实现类: " + embeddingModel.getClass().getName());

        try {
            float[] vec = embeddingModel.embed("测试文本");
            System.out.println("✅ 成功！向量维度: " + vec.length);
        } catch (Exception e) {
            System.out.println("❌ 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    void printEnvKey(){
        String key = System.getenv("DASHSCOPE_API_KEY");
        System.out.println("=====DASHSCOPE_API_KEY=====");
        System.out.println(key);
        System.out.println("长度："+key.length());
    }

    @Test
    void printPublicIp() {
        // 获取程序当前的出口IP（Spring程序发请求用的IP）
        RestTemplate restTemplate = new RestTemplate();
        String ip = restTemplate.getForObject("https://ifconfig.me/ip", String.class);
        System.out.println("Spring程序出口IP：" + ip);
    }


    @Test
    void rawRestTemplateTestWithHost() {
        String url = "https://llm-flmgbipyi282dygn.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/embeddings";
        String apiKey = "sk-ws-H.EDYHMLX.oN7W.MEQCIFv8JTcl6EG0Vrtg8Lb1sPFGfZ4EHEc-RT5gWaDMH8HjAiB_NpwXVczXBb8WSRWtm3wLuE5JQcTIfX95f4-lR6f0Rg";
        String body = "{\"input\":\"测试文本\",\"model\":\"text-embedding-v4\",\"dimensions\":1024}";

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        // 手动固定Host头，和curl完全一致
        headers.set("Host", "llm-flmgbipyi282dygn.cn-beijing.maas.aliyuncs.com");
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
        System.out.println("响应：" + resp.getBody());
    }


    @Test
    void checkTlsVersion() {
        System.out.println("JDK版本:" + System.getProperty("java.version"));
        System.out.println("TLS默认:" + System.getProperty("https.protocols"));
    }



}