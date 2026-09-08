package com.argus.rag.ingestion.service.pipeline.transformer;

import java.util.List;

/**
 * 分块结果校验器，对应 WeKnora {@code chunker.validator.go}。
 *
 * <p>有意宽松：只拒绝"明显损坏"的输出，接受看似合理的变异，
 * 避免在层级之间来回振荡。</p>
 *
 * @author Argus-RAG Team
 * @since 1.0.0
 */
public final class ChunkValidator {

    private ChunkValidator() {
    }

    /** 校验结果 */
    public record ValidationResult(boolean ok, String reason) {
        static ValidationResult success() {
            return new ValidationResult(true, "");
        }

        static ValidationResult reject(String reason) {
            return new ValidationResult(false, reason);
        }
    }

    /**
     * 校验分块结果是否可用。
     *
     * @param chunks     分块列表
     * @param totalChars 文档总字符数（UTF-16 字符）
     * @param chunkSize  目标分块大小（字符）
     * @return 校验结果
     */
    public static ValidationResult validate(List<Chunk> chunks, int totalChars, int chunkSize) {
        if (chunks == null || chunks.isEmpty()) {
            return ValidationResult.reject("no chunks produced");
        }
        if (chunks.size() == 1 && totalChars > 2 * chunkSize) {
            return ValidationResult.reject("single chunk for large document");
        }

        double sum = 0;
        double sumSq = 0;
        long maxLen = 0;
        long minLen = Long.MAX_VALUE;
        for (Chunk c : chunks) {
            long length = c.contentLength();
            sum += length;
            sumSq += (double) length * length;
            maxLen = Math.max(maxLen, length);
            minLen = Math.min(minLen, length);
        }

        long tinyCount = 0;
        for (int i = 0; i < chunks.size(); i++) {
            if (i == chunks.size() - 1) {
                continue;
            }
            if (chunks.get(i).contentLength() < 50) {
                tinyCount++;
            }
        }
        if (tinyCount > chunks.size() / 4 && tinyCount > 2) {
            return ValidationResult.reject("too many tiny chunks");
        }

        if (maxLen < chunkSize / 4 && totalChars > chunkSize) {
            return ValidationResult.reject("all chunks far below target size");
        }

        if (maxLen > 2L * chunkSize && chunkSize > 0) {
            return ValidationResult.reject("chunk exceeds 2x target size");
        }

        return ValidationResult.success();
    }
}

