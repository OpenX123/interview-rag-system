package interview.rag.system.common.ai.web;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tavily 联网搜索配置
 * 对应 application.yml 中 app.ai.web-search.tavily.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.web-search.tavily")
public class TavilySearchProperties {

    /** API Key（为空则视为未启用） */
    private String apiKey = "";

    /** Tavily API Base URL */
    private String baseUrl = "https://api.tavily.com";

    /** 每次返回的最大结果数 */
    private int maxResults = 5;

    /** 搜索深度：basic / advanced */
    private String searchDepth = "basic";

    /** HTTP 调用超时（秒） */
    private int timeoutSeconds = 10;

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
