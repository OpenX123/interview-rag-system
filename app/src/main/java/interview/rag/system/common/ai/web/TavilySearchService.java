package interview.rag.system.common.ai.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Tavily 联网搜索服务
 * <p>
 * 通过 Tavily REST API（POST {baseUrl}/search）进行实时联网检索，
 * 返回的结构化结果会拼接成纯文本片段供 RAG Prompt 使用。
 * <p>
 * 设计要点：
 * - 失败容错：网络/HTTP 异常时返回空结果，不阻断 RAG 主流程
 * - 短超时：默认 10s，避免拖慢流式响应首字节
 * - 懒初始化 RestClient：未启用时不占用资源
 */
@Slf4j
@Service
public class TavilySearchService {

    private final TavilySearchProperties properties;
    private final RestClient restClient;

    public TavilySearchService(TavilySearchProperties properties) {
        this.properties = properties;
        this.restClient = properties.isEnabled()
            ? buildRestClient(properties)
            : null;
        if (properties.isEnabled()) {
            log.info("Tavily 联网搜索已启用: baseUrl={}, maxResults={}, depth={}",
                properties.getBaseUrl(), properties.getMaxResults(), properties.getSearchDepth());
        } else {
            log.info("Tavily 联网搜索未配置 API Key，已禁用");
        }
    }

    /**
     * 调用 Tavily 联网搜索。
     *
     * @param query 用户问题（已重写/归一化后的查询更佳）
     * @return 搜索结果列表；若未启用、调用失败或无结果则返回空列表
     */
    public List<TavilyResult> search(String query) {
        if (!properties.isEnabled() || restClient == null) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {
            Map<String, Object> body = Map.of(
                "api_key", properties.getApiKey(),
                "query", query,
                "search_depth", properties.getSearchDepth(),
                "max_results", properties.getMaxResults(),
                "include_answer", false,
                "include_raw_content", false,
                "include_images", false
            );

            TavilyResponse response = restClient.post()
                .uri("/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(TavilyResponse.class);

            if (response == null || response.results() == null) {
                log.warn("Tavily 联网搜索返回空响应: query={}", query);
                return List.of();
            }
            log.info("Tavily 联网搜索完成: query='{}', hits={}", query, response.results().size());
            return response.results();

        } catch (HttpStatusCodeException e) {
            log.warn("Tavily 联网搜索 HTTP 异常: status={}, body={}",
                e.getStatusCode(), e.getResponseBodyAsString());
            return List.of();
        } catch (ResourceAccessException e) {
            log.warn("Tavily 联网搜索超时或网络异常: {}", e.getMessage());
            return List.of();
        } catch (Exception e) {
            log.warn("Tavily 联网搜索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 将搜索结果格式化为 RAG context 片段。
     * 空列表返回空串，调用方需自行判断是否拼接到主 context。
     */
    public String formatAsContext(List<TavilyResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            TavilyResult r = results.get(i);
            sb.append("[网络来源 ").append(i + 1).append("] ")
              .append(safe(r.title())).append("\n")
              .append("URL: ").append(safe(r.url())).append("\n")
              .append(safe(r.content())).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private static RestClient buildRestClient(TavilySearchProperties props) {
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(props.getTimeoutSeconds());
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
            .baseUrl(props.getBaseUrl())
            .requestFactory(requestFactory)
            .build();
    }

    // ========== Response 模型 ==========

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TavilyResponse(
        String query,
        List<TavilyResult> results
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TavilyResult(
        String title,
        String url,
        String content,
        @JsonProperty("score") Double score,
        @JsonProperty("published_date") String publishedDate
    ) {}
}
