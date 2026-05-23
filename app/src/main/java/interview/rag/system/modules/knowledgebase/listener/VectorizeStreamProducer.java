package interview.rag.system.modules.knowledgebase.listener;

import interview.rag.system.common.async.AbstractStreamProducer;
import interview.rag.system.common.constant.AsyncTaskStreamConstants;
import interview.rag.system.infrastructure.redis.RedisService;
import interview.rag.system.modules.knowledgebase.model.VectorStatus;
import interview.rag.system.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 向量化任务生产者
 * 负责发送向量化任务到 Redis Stream
 */
@Slf4j
@Component
public class VectorizeStreamProducer extends AbstractStreamProducer<VectorizeStreamProducer.VectorizeTaskPayload> {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /**
     * @param chunkSize 用户指定的分块大小（token 数），null 表示用 TokenTextSplitter 默认
     * @param embeddingProvider 用户指定的 embedding provider id，null 表示用全局默认
     */
    record VectorizeTaskPayload(Long kbId, String content, Integer chunkSize, String embeddingProvider) {}

    public VectorizeStreamProducer(RedisService redisService, KnowledgeBaseRepository knowledgeBaseRepository) {
        super(redisService);
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * 发送向量化任务到 Redis Stream
     *
     * @param kbId    知识库ID
     * @param content 文档内容
     * @param chunkSize 用户指定分块大小，null 表示用默认
     * @param embeddingProvider 用户指定 embedding provider，null 表示用全局默认
     */
    public void sendVectorizeTask(Long kbId, String content, Integer chunkSize, String embeddingProvider) {
        sendTask(new VectorizeTaskPayload(kbId, content, chunkSize, embeddingProvider));
    }

    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(VectorizeTaskPayload payload) {
        Map<String, String> msg = new HashMap<>();
        msg.put(AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString());
        msg.put(AsyncTaskStreamConstants.FIELD_CONTENT, payload.content());
        msg.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");
        if (payload.chunkSize() != null) {
            msg.put(AsyncTaskStreamConstants.FIELD_CHUNK_SIZE, String.valueOf(payload.chunkSize()));
        }
        if (payload.embeddingProvider() != null && !payload.embeddingProvider().isBlank()) {
            msg.put(AsyncTaskStreamConstants.FIELD_EMBEDDING_PROVIDER, payload.embeddingProvider());
        }
        return msg;
    }

    @Override
    protected String payloadIdentifier(VectorizeTaskPayload payload) {
        return "kbId=" + payload.kbId();
    }

    @Override
    protected void onSendFailed(VectorizeTaskPayload payload, String error) {
        updateVectorStatus(payload.kbId(), VectorStatus.FAILED, truncateError(error));
    }

    /**
     * 更新向量化状态
     */
    private void updateVectorStatus(Long kbId, VectorStatus status, String error) {
        knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
            kb.setVectorStatus(status);
            if (error != null) {
                kb.setVectorError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            knowledgeBaseRepository.save(kb);
        });
    }
}
