package com.dbappsecurity.prd2api.rag;

import cn.hutool.core.util.StrUtil;
import com.dbappsecurity.prd2api.common.BizException;
import com.dbappsecurity.prd2api.config.Prd2ApiProperties;
import com.dbappsecurity.prd2api.constant.BizErrorCodeEnum;
import com.dbappsecurity.prd2api.constant.RedisKeyConstant;
import com.dbappsecurity.prd2api.vo.RagChunkVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Chroma 向量库服务（HTTP API）
 * <p>
 * 每个任务独立 collection，租户 + 任务双重隔离，避免上下文串扰。
 * 说明：未引入第三方 chroma 客户端 jar，直接调用 Chroma HTTP API，便于版本升级与灰度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChromaVectorService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long COLLECTION_TTL_DAYS = 7L;

    private final OkHttpClient okHttpClient;
    private final Prd2ApiProperties properties;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 向量库连通性探测
     */
    public boolean heartbeat() {
        String base = baseUrl();
        for (String path : new String[]{"/api/v1/heartbeat", "/api/v2/heartbeat", "/api/v1/version"}) {
            Request request = new Request.Builder().url(base + path).get().build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    return true;
                }
            } catch (Exception e) {
                log.debug("Chroma 探测失败 path={} msg={}", path, e.getMessage());
            }
        }
        return false;
    }

    /**
     * collection 引用：Chroma 写入/查询使用 id，删除接口使用 name
     */
    public record CollectionRef(String id, String name) {
    }

    /**
     * 获取或创建任务级 collection，引用缓存到 Redis
     */
    public CollectionRef ensureCollection(String tenantId, String taskId) {
        CollectionRef cached = cachedCollection(taskId);
        if (cached != null) {
            return cached;
        }
        String name = collectionName(tenantId, taskId);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("get_or_create", true);
        body.putObject("metadata").put("hnsw:space", "cosine");

        JsonNode response = postJson(baseUrl() + "/api/v1/collections" + tenantQuery(), body);
        String collectionId = response.path("id").asText();
        if (StrUtil.isBlank(collectionId)) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "Chroma 创建 collection 失败: " + response);
        }
        CollectionRef ref = new CollectionRef(collectionId, name);
        try {
            stringRedisTemplate.opsForValue().set(RedisKeyConstant.ragCollection(taskId),
                    objectMapper.writeValueAsString(ref), COLLECTION_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("collection 引用缓存写入失败 taskId={}", taskId, e);
        }
        log.info("Chroma collection 就绪 taskId={} collectionId={}", taskId, collectionId);
        return ref;
    }

    /**
     * 获取或创建指定名称的 collection（知识库等全局集合，不按任务缓存）
     */
    public CollectionRef ensureNamedCollection(String name) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("get_or_create", true);
        body.putObject("metadata").put("hnsw:space", "cosine");

        JsonNode response = postJson(baseUrl() + "/api/v1/collections" + tenantQuery(), body);
        String collectionId = response.path("id").asText();
        if (StrUtil.isBlank(collectionId)) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "Chroma 创建 collection 失败: " + response);
        }
        return new CollectionRef(collectionId, name);
    }

    /**
     * 读取缓存的 collection 引用，缓存丢失或格式异常返回 null
     */
    public CollectionRef cachedCollection(String taskId) {
        String cached = stringRedisTemplate.opsForValue().get(RedisKeyConstant.ragCollection(taskId));
        if (StrUtil.isBlank(cached)) {
            return null;
        }
        try {
            return objectMapper.readValue(cached, CollectionRef.class);
        } catch (Exception e) {
            log.warn("collection 引用缓存解析失败，按未命中处理 taskId={}", taskId);
            stringRedisTemplate.delete(RedisKeyConstant.ragCollection(taskId));
            return null;
        }
    }

    /**
     * 批量写入分块向量
     */
    public void addChunks(String collectionId, List<String> ids, List<float[]> embeddings,
                          List<String> documents, List<String> sources) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode idArray = body.putArray("ids");
        ids.forEach(idArray::add);
        ArrayNode embeddingArray = body.putArray("embeddings");
        for (float[] embedding : embeddings) {
            ArrayNode one = embeddingArray.addArray();
            for (float value : embedding) {
                one.add(value);
            }
        }
        ArrayNode documentArray = body.putArray("documents");
        documents.forEach(documentArray::add);
        ArrayNode metadataArray = body.putArray("metadatas");
        for (String source : sources) {
            metadataArray.addObject().put("source", source);
        }
        postJson(baseUrl() + "/api/v1/collections/" + collectionId + "/add" + tenantQuery(), body);
        log.info("Chroma 写入分块完成 collectionId={} size={}", collectionId, ids.size());
    }

    /**
     * 向量召回
     */
    public List<RagChunkVO> query(String collectionId, float[] queryVector, int topK) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode embeddingArray = body.putArray("query_embeddings");
        ArrayNode one = embeddingArray.addArray();
        for (float value : queryVector) {
            one.add(value);
        }
        body.put("n_results", Math.max(1, topK));
        ArrayNode include = body.putArray("include");
        include.add("documents");
        include.add("distances");
        include.add("metadatas");

        JsonNode root = postJson(baseUrl() + "/api/v1/collections/" + collectionId + "/query" + tenantQuery(), body);
        JsonNode documents = root.path("documents").path(0);
        JsonNode distances = root.path("distances").path(0);
        JsonNode metadatas = root.path("metadatas").path(0);

        List<RagChunkVO> result = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            String content = documents.get(i).asText();
            double distance = distances.size() > i ? distances.get(i).asDouble() : 1.0D;
            String source = metadatas.size() > i ? metadatas.get(i).path("source").asText("vector") : "vector";
            result.add(new RagChunkVO(content, source, Math.max(0.0D, 1.0D - distance)));
        }
        return result;
    }

    /**
     * 删除 collection（任务删除 / 租户清理）
     * <p>
     * Chroma v1 删除接口按名称定位，失败时兜底尝试 id。
     */
    public void deleteCollection(CollectionRef ref) {
        if (ref == null) {
            return;
        }
        if (!deleteByKey(ref.name())) {
            deleteByKey(ref.id());
        }
    }

    private boolean deleteByKey(String key) {
        if (StrUtil.isBlank(key)) {
            return true;
        }
        Request request = new Request.Builder()
                .url(baseUrl() + "/api/v1/collections/" + key + tenantQuery())
                .delete()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return true;
            }
            log.warn("Chroma 删除 collection 失败 key={} http={}", key, response.code());
            return false;
        } catch (Exception e) {
            log.warn("Chroma 删除 collection 异常 key={} msg={}", key, e.getMessage());
            return false;
        }
    }

    private JsonNode postJson(String url, ObjectNode body) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new BizException(BizErrorCodeEnum.SYSTEM_ERROR, "Chroma 请求体序列化失败", e);
        }
        Request request = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload, JSON))
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new BizException(BizErrorCodeEnum.BIZ_ERROR,
                        "Chroma 调用失败 http=" + response.code() + " body=" + StrUtil.maxLength(responseBody, 300));
            }
            if (StrUtil.isBlank(responseBody)) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(responseBody);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(BizErrorCodeEnum.BIZ_ERROR, "Chroma 调用异常: " + e.getMessage(), e);
        }
    }

    private String baseUrl() {
        return StrUtil.removeSuffix(properties.getRag().getChromaUrl(), "/");
    }

    private String tenantQuery() {
        String tenant = URLEncoder.encode(properties.getRag().getChromaTenant(), StandardCharsets.UTF_8);
        String database = URLEncoder.encode(properties.getRag().getChromaDatabase(), StandardCharsets.UTF_8);
        return "?tenant=" + tenant + "&database=" + database;
    }

    /**
     * Chroma collection 名称限制：3~63 位，仅字母数字及 ._- ，首尾为字母数字
     */
    private String collectionName(String tenantId, String taskId) {
        String raw = properties.getRag().getCollectionPrefix() + "-" + tenantId + "-" + taskId;
        String name = raw.toLowerCase().replaceAll("[^a-z0-9._-]", "-");
        if (name.length() > 63) {
            name = name.substring(name.length() - 63);
        }
        if (name.length() < 3) {
            name = "prd-" + name;
        }
        return name.replaceAll("^[^a-z0-9]+", "").replaceAll("[^a-z0-9]+$", "");
    }
}
