package interview.rag.system.modules.knowledgebase.repository;

import interview.rag.system.common.exception.BusinessException;
import interview.rag.system.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 向量存储Repository
 * 负责向量数据的增删改查操作
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class VectorRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 删除指定知识库的所有向量数据
     * 使用 SQL 直接删除，利用数据库索引和删除能力
     * <p>
     * Spring AI PgVectorStore 默认表名为 vector_store，元数据存储在 metadata 字段（JSONB类型）
     *
     * @param knowledgeBaseId 知识库ID
     * @return 删除的行数
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        log.info("开始删除知识库向量数据: kbId={}", knowledgeBaseId);

        /*
         * 注意：
         * 1. metadata 字段是 json 类型，不支持 jsonb_exists 函数。
         * 2. 使用 metadata->>'key' IS NOT NULL 来替代键存在性检查，这在 json/jsonb 下都有效。
         * 3. 这种写法完全避开了 PostgreSQL 的 '?' 操作符，不会引起 JDBC 占位符冲突。
         */
        String sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_id' = ?
               OR (metadata->>'kb_id_long' IS NOT NULL AND (metadata->>'kb_id_long')::bigint = ?)
            """;

        try {
            // 第一个参数转为 String 匹配 kb_id，第二个参数保持 Long 匹配 kb_id_long
            int deletedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), knowledgeBaseId);

            if (deletedRows > 0) {
                log.info("成功删除知识库向量数据: kbId={}, 删除行数={}", knowledgeBaseId, deletedRows);
            } else {
                log.info("未找到相关向量数据，无需删除: kbId={}", knowledgeBaseId);
            }

            return deletedRows;

        } catch (Exception e) {
            log.error("执行删除向量 SQL 失败: kbId={}, error={}", knowledgeBaseId, e.getMessage());
            // 抛出异常以触发事务回滚
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败");
        }
    }

    /**
     * 查询指定知识库的所有向量块（按 chunk_index 升序）。
     * 不返回 embedding 向量本身（1024 维 float[]，无展示价值且体积大）。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return chunks，按 chunk_index 升序；每行 keys: id / content / chunk_index / total_chunks / parent_document_id
     */
    public List<Map<String, Object>> findChunksByKnowledgeBaseId(Long knowledgeBaseId) {
        String sql = """
            SELECT
                id::text                                  AS id,
                content,
                COALESCE((metadata->>'chunk_index')::int, 0)   AS chunk_index,
                COALESCE((metadata->>'total_chunks')::int, 0)  AS total_chunks,
                metadata->>'parent_document_id'                 AS parent_document_id
            FROM vector_store
            WHERE metadata->>'kb_id' = ?
               OR (metadata->>'kb_id_long' IS NOT NULL AND (metadata->>'kb_id_long')::bigint = ?)
            ORDER BY chunk_index ASC, id ASC
            """;
        return jdbcTemplate.queryForList(sql, knowledgeBaseId.toString(), knowledgeBaseId);
    }

    /**
     * 统计指定知识库的向量块数量。
     */
    public int countByKnowledgeBaseId(Long knowledgeBaseId) {
        String sql = """
            SELECT COUNT(*) FROM vector_store
            WHERE metadata->>'kb_id' = ?
               OR (metadata->>'kb_id_long' IS NOT NULL AND (metadata->>'kb_id_long')::bigint = ?)
            """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class,
            knowledgeBaseId.toString(), knowledgeBaseId);
        return count == null ? 0 : count;
    }

    /**
     * 直接向 vector_store 表插入一条向量记录。
     * 用于使用「指定 EmbeddingModel」时绕过 Spring AI 的 VectorStore 单例绑定。
     * <p>
     * 注意：
     * - id 由 PostgreSQL uuid_generate_v4() 生成
     * - metadataJson 必须是合法 JSON 字符串（用 ObjectMapper 序列化），通过 ::json 强转写入
     * - embedding 通过 ::vector 强转写入 pgvector 列，格式如 "[0.1,0.2,...]"
     */
    public void insertVector(String content, String metadataJson, float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("embedding 不能为空");
        }
        String vectorLiteral = toVectorLiteral(embedding);
        String sql = """
            INSERT INTO vector_store (content, metadata, embedding)
            VALUES (?, ?::json, ?::vector)
            """;
        jdbcTemplate.update(sql, content, metadataJson, vectorLiteral);
    }

    /**
     * pgvector 字面量格式：[v1,v2,v3,...]，无空格以缩小传输体积。
     */
    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length * 8);
        sb.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            // 使用 Float.toString 保留默认精度，pgvector 接收 float4
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
