package interview.rag.system.modules.knowledgebase.listener;

import interview.rag.system.common.async.AbstractStreamConsumer;
import interview.rag.system.common.constant.AsyncTaskStreamConstants;
import interview.rag.system.infrastructure.redis.RedisService;
import interview.rag.system.modules.knowledgebase.model.VectorStatus;
import interview.rag.system.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.rag.system.modules.knowledgebase.service.KnowledgeBaseVectorService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识库向量化 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行向量化
 */
@Slf4j
@Component
public class VectorizeStreamConsumer extends AbstractStreamConsumer<VectorizeStreamConsumer.VectorizePayload> {

    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public VectorizeStreamConsumer(
        RedisService redisService,
        KnowledgeBaseVectorService vectorService,
        KnowledgeBaseRepository knowledgeBaseRepository
    ) {
        super(redisService);
        this.vectorService = vectorService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * @param chunkSize 用户指定的分块大小（token 数），null 表示用默认
     * @param embeddingProvider 用户指定的 embedding provider，null 表示用全局默认
     */
    record VectorizePayload(Long kbId, String content, Integer chunkSize, String embeddingProvider) {}

    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "vectorize-consumer";
    }

    @Override
    protected VectorizePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String kbIdStr = data.get(AsyncTaskStreamConstants.FIELD_KB_ID);
        String content = data.get(AsyncTaskStreamConstants.FIELD_CONTENT);
        if (kbIdStr == null || content == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        Integer chunkSize = parseInt(data.get(AsyncTaskStreamConstants.FIELD_CHUNK_SIZE));
        String embeddingProvider = nullIfBlank(data.get(AsyncTaskStreamConstants.FIELD_EMBEDDING_PROVIDER));
        return new VectorizePayload(Long.parseLong(kbIdStr), content, chunkSize, embeddingProvider);
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    @Override
    protected String payloadIdentifier(VectorizePayload payload) {
        return "kbId=" + payload.kbId();
    }

    @Override
    protected void markProcessing(VectorizePayload payload) {
        updateVectorStatus(payload.kbId(), VectorStatus.PROCESSING, null);
    }

    @Override
    protected void processBusiness(VectorizePayload payload) {
        int actualChunks = vectorService.vectorizeAndStore(
            payload.kbId(),
            payload.content(),
            payload.chunkSize(),
            payload.embeddingProvider()
        );
        // 立即回填 chunkCount，避免列表统计字段一直显示 0
        knowledgeBaseRepository.findById(payload.kbId()).ifPresent(kb -> {
            kb.setChunkCount(actualChunks);
            knowledgeBaseRepository.save(kb);
        });
    }

    @Override
    protected void markCompleted(VectorizePayload payload) {
        updateVectorStatus(payload.kbId(), VectorStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(VectorizePayload payload, String error) {
        updateVectorStatus(payload.kbId(), VectorStatus.FAILED, error);
    }

    @Override
    protected void retryMessage(VectorizePayload payload, int retryCount) {
        Long kbId = payload.kbId();
        String content = payload.content();
        try {
            java.util.Map<String, String> message = new java.util.HashMap<>();
            message.put(AsyncTaskStreamConstants.FIELD_KB_ID, kbId.toString());
            message.put(AsyncTaskStreamConstants.FIELD_CONTENT, content);
            message.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount));
            if (payload.chunkSize() != null) {
                message.put(AsyncTaskStreamConstants.FIELD_CHUNK_SIZE, String.valueOf(payload.chunkSize()));
            }
            if (payload.embeddingProvider() != null) {
                message.put(AsyncTaskStreamConstants.FIELD_EMBEDDING_PROVIDER, payload.embeddingProvider());
            }

            redisService().streamAdd(
                AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("向量化任务已重新入队: kbId={}, retryCount={}", kbId, retryCount);

        } catch (Exception e) {
            log.error("重试入队失败: kbId={}, error={}", kbId, e.getMessage(), e);
            updateVectorStatus(kbId, VectorStatus.FAILED, truncateError("重试入队失败: " + e.getMessage()));
        }
    }

    /**
     * 更新向量化状态
     */
    private void updateVectorStatus(Long kbId, VectorStatus status, String error) {
        try {
            knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
                kb.setVectorStatus(status);
                kb.setVectorError(error);
                knowledgeBaseRepository.save(kb);
                log.debug("向量化状态已更新: kbId={}, status={}", kbId, status);
            });
        } catch (Exception e) {
            log.error("更新向量化状态失败: kbId={}, status={}, error={}", kbId, status, e.getMessage(), e);
        }
    }

}
