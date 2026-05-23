package interview.rag.system.modules.knowledgebase.model;

/**
 * 知识库向量块 DTO，用于「查看分块」展示。
 * 不暴露原始 embedding 数组（1024 维 float），仅返回切片文本与位置信息。
 */
public record KnowledgeBaseChunkDTO(
    String id,                 // vector_store.id (UUID 字符串)
    int chunkIndex,            // 在该文档内的顺序，从 0 开始
    int totalChunks,           // 该文档被切成的总块数
    String parentDocumentId,   // Spring AI 分配的父文档 UUID，可能为 null
    String content,            // 切片文本
    int contentLength          // 切片文本字符数，前端无需自己 length()
) {
}
