package interview.rag.system.modules.voiceinterview.listener;

import interview.rag.system.common.async.AbstractStreamProducer;
import interview.rag.system.common.constant.AsyncTaskStreamConstants;
import interview.rag.system.common.model.AsyncTaskStatus;
import interview.rag.system.infrastructure.redis.RedisService;
import interview.rag.system.modules.voiceinterview.service.VoiceInterviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 语音面试评估任务生产者
 */
@Slf4j
@Component
public class VoiceEvaluateStreamProducer extends AbstractStreamProducer<String> {

    private final VoiceInterviewService voiceInterviewService;

    public VoiceEvaluateStreamProducer(RedisService redisService,
                                       @Lazy VoiceInterviewService voiceInterviewService) {
        super(redisService);
        this.voiceInterviewService = voiceInterviewService;
    }

    public void sendEvaluateTask(String sessionId) {
        sendTask(sessionId);
    }

    @Override
    protected String taskDisplayName() {
        return "语音面试评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(String sessionId) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, sessionId,
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(String sessionId) {
        return "voiceSessionId=" + sessionId;
    }

    @Override
    protected void onSendFailed(String sessionId, String error) {
        voiceInterviewService.updateEvaluateStatus(
                Long.parseLong(sessionId), AsyncTaskStatus.FAILED, truncateError(error));
    }
}
