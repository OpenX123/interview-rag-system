package interview.rag.system.modules.knowledgebase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.rag.system.common.ai.LlmProviderRegistry;
import interview.rag.system.common.exception.BusinessException;
import interview.rag.system.common.exception.ErrorCode;
import interview.rag.system.modules.knowledgebase.repository.VectorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 知识库向量存储服务
 * 负责文档分块、向量化和检索
 */
@Slf4j
@Service
public class KnowledgeBaseVectorService {

    /** 阿里云 DashScope Embedding API 单次最大批量 */
    private static final int MAX_BATCH_SIZE = 10;

    /** 默认分块大小（token 数），与 Spring AI TokenTextSplitter 内置默认一致 */
    public static final int DEFAULT_CHUNK_SIZE = 800;

    /** 预览时最多返回的分块数（避免大文档把网络/前端打爆） */
    public static final int PREVIEW_MAX_CHUNKS = 100;

    private final VectorStore vectorStore;
    private final VectorRepository vectorRepository;
    private final LlmProviderRegistry llmProviderRegistry;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseVectorService(
        VectorStore vectorStore,
        VectorRepository vectorRepository,
        LlmProviderRegistry llmProviderRegistry,
        ObjectMapper objectMapper
    ) {
        this.vectorStore = vectorStore;
        this.vectorRepository = vectorRepository;
        this.llmProviderRegistry = llmProviderRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 将文本切分成 chunks（不向量化、不入库）。
     * 用于「预览分块」接口与向量化前的切分阶段共享同一份逻辑，确保用户预览到的就是真正入库的内容。
     *
     * @param content   文本内容
     * @param chunkSize 分块大小（token 数）。null/<=0 时回退到 {@link #DEFAULT_CHUNK_SIZE}
     * @return Document 列表（按顺序）
     */
    public List<Document> splitContent(String content, Integer chunkSize) {
        int effectiveChunkSize = (chunkSize == null || chunkSize <= 0) ? DEFAULT_CHUNK_SIZE : chunkSize;
        TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(effectiveChunkSize)
            .build();
        return splitter.apply(List.of(new Document(content)));
    }

    /**
     * 将知识库内容向量化并存储。
     * <p>
     * 当 {@code embeddingProvider} 为空时，沿用 Spring AI {@link VectorStore} 自动注入的默认 EmbeddingModel；
     * 否则用 {@link LlmProviderRegistry} 取出用户指定的 EmbeddingModel，对每个 chunk 单独 embed，
     * 再通过 {@link VectorRepository#insertVector} JDBC 直接写入 pgvector，
     * 绕过 VectorStore 与默认 EmbeddingModel 的绑定。
     *
     * @return 实际入库的 chunks 数（用于回填 KnowledgeBaseEntity.chunkCount）
     */
    @Transactional
    public int vectorizeAndStore(Long knowledgeBaseId, String content,
                                 Integer chunkSize, String embeddingProvider) {
        log.info("开始向量化知识库: kbId={}, contentLength={}, chunkSize={}, provider={}",
            knowledgeBaseId, content.length(), chunkSize, embeddingProvider);
        try {
            // 1. 删除旧向量
            deleteByKnowledgeBaseId(knowledgeBaseId);

            // 2. 切分
            List<Document> chunks = splitContent(content, chunkSize);
            int totalChunks = chunks.size();
            log.info("文本分块完成: {} 个chunks (chunkSize={})", totalChunks, chunkSize);

            if (totalChunks == 0) {
                log.warn("内容切分为 0 块，跳过入库: kbId={}", knowledgeBaseId);
                return 0;
            }

            // 3. 为每个 chunk 注入 metadata
            for (int i = 0; i < totalChunks; i++) {
                Document doc = chunks.get(i);
                doc.getMetadata().put("kb_id", knowledgeBaseId.toString());
                doc.getMetadata().put("chunk_index", i);
                doc.getMetadata().put("total_chunks", totalChunks);
            }

            // 4. 选路：指定 provider 走 JDBC 直插；未指定走默认 VectorStore
            if (embeddingProvider != null && !embeddingProvider.isBlank()) {
                vectorizeWithProvider(chunks, embeddingProvider);
            } else {
                vectorizeWithDefaultStore(chunks);
            }

            log.info("知识库向量化完成: kbId={}, chunks={}", knowledgeBaseId, totalChunks);
            return totalChunks;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("向量化知识库失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "向量化知识库失败: " + e.getMessage());
        }
    }

    /**
     * 走 Spring AI VectorStore 默认路径（默认 EmbeddingModel，自动批量）。
     */
    private void vectorizeWithDefaultStore(List<Document> chunks) {
        int total = chunks.size();
        int batchCount = (total + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE;
        log.info("默认 VectorStore 路径: 总 {} chunks，{} 批", total, batchCount);
        for (int i = 0; i < batchCount; i++) {
            int start = i * MAX_BATCH_SIZE;
            int end = Math.min(start + MAX_BATCH_SIZE, total);
            vectorStore.add(chunks.subList(start, end));
        }
    }

    /**
     * 用指定 provider 的 EmbeddingModel 向量化，结果通过 JDBC 直插 vector_store。
     */
    private void vectorizeWithProvider(List<Document> chunks, String providerId) {
        EmbeddingModel embeddingModel = llmProviderRegistry.getEmbeddingModel(providerId);
        if (embeddingModel == null) {
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE,
                "未找到 embedding provider: " + providerId);
        }

        int total = chunks.size();
        log.info("自定义 provider 路径: provider={}, 总 {} chunks", providerId, total);

        for (int i = 0; i < total; i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, total);
            List<Document> batch = chunks.subList(i, end);

            // Spring AI 2.0 EmbeddingModel 没有 embed(List<Document>) 重载，
            // 只能用 embed(List<String>) — 先提取每个 chunk 的文本
            List<String> texts = batch.stream()
                .map(d -> d.getText() == null ? "" : d.getText())
                .toList();
            List<float[]> embeddings = embeddingModel.embed(texts);
            if (embeddings.size() != batch.size()) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                    "embedding 数量与 chunk 数量不一致: expected="
                        + batch.size() + ", got=" + embeddings.size());
            }

            for (int j = 0; j < batch.size(); j++) {
                Document doc = batch.get(j);
                float[] vec = embeddings.get(j);
                String metaJson = toJson(doc.getMetadata());
                vectorRepository.insertVector(texts.get(j), metaJson, vec);
            }
            log.debug("provider={} 写入第 {}/{} 批", providerId, (i / MAX_BATCH_SIZE) + 1,
                (total + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE);
        }
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            // 保留键序，方便调试时阅读
            return objectMapper.writeValueAsString(new LinkedHashMap<>(metadata));
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "metadata 序列化失败: " + e.getMessage());
        }
    }

    /**
     * 基于多个知识库进行相似度搜索
     */
    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        log.info("向量相似度搜索: query={}, kbIds={}, topK={}, minScore={}",
            query, knowledgeBaseIds, topK, minScore);

        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK, 1));

            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            if (results == null) {
                return List.of();
            }

            List<Document> limitedResults = results.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("搜索完成: 找到 {} 个相关文档", limitedResults.size());
            return limitedResults;

        } catch (Exception e) {
            log.warn("向量搜索前置过滤失败，回退到本地过滤: {}", e.getMessage());
            return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore);
        }
    }

    private List<Document> similaritySearchFallback(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK * 3, topK));
            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            List<Document> allResults = vectorStore.similaritySearch(builder.build());
            if (allResults == null || allResults.isEmpty()) {
                return List.of();
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                allResults = allResults.stream()
                    .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds))
                    .collect(Collectors.toList());
            }

            List<Document> results = allResults.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("回退检索完成: 找到 {} 个相关文档", results.size());
            return results;
        } catch (Exception e) {
            log.error("向量搜索失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
                "向量搜索失败: " + e.getMessage());
        }
    }

    private boolean isDocInKnowledgeBases(Document doc, List<Long> knowledgeBaseIds) {
        Object kbId = doc.getMetadata().get("kb_id");
        if (kbId == null) {
            return false;
        }
        try {
            Long kbIdLong = kbId instanceof Long
                ? (Long) kbId
                : Long.parseLong(kbId.toString());
            return knowledgeBaseIds.contains(kbIdLong);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String buildKbFilterExpression(List<Long> knowledgeBaseIds) {
        String values = knowledgeBaseIds.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .map(id -> "'" + id + "'")
            .collect(Collectors.joining(", "));
        return "kb_id in [" + values + "]";
    }

    /**
     * 删除指定知识库的所有向量数据
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        try {
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
        } catch (Exception e) {
            log.error("删除向量数据失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
        }
    }
}
