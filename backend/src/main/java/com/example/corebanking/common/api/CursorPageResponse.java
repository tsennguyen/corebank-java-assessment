package com.example.corebanking.common.api;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * Keyset (cursor-based) pagination response envelope providing constant {@code O(log N)} seek
 * latency regardless of scroll depth (Story 6.1: NC-13, G-05, L2-04).
 *
 * @param <T> element type in the page content
 * @param content items in the current slice ordered by {@code (createdAt DESC, id DESC)}
 * @param size requested page size
 * @param nextCursor opaque Base64URL cursor pointing to the last item in {@code content}, or {@code
 *     null} when on the final page
 * @param hasNext {@code true} if additional items exist after {@code content}
 */
public record CursorPageResponse<T>(
        List<T> content, int size, @Nullable String nextCursor, boolean hasNext) {

    /**
     * Encodes a composite keyset cursor {@code createdAt|id} using URL-safe Base64 without padding.
     *
     * @param createdAt timestamp of the last row in the current page
     * @param id database identifier of the last row in the current page
     * @return opaque Base64URL cursor token
     */
    public static String encodeCursor(Instant createdAt, Long id) {
        if (createdAt == null || id == null) {
            throw new IllegalArgumentException("Cursor createdAt and id must not be null");
        }
        String raw = createdAt.toString() + "|" + id;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes and validates an opaque Base64URL keyset cursor token.
     *
     * @param cursor opaque Base64URL string supplied by the client
     * @return decoded {@link DecodedCursor} containing {@code (createdAt, id)}
     * @throws BusinessRuleException with {@link ErrorCode#INVALID_CURSOR} (HTTP 400) if tampered or
     *     malformed
     */
    public static DecodedCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw new BusinessRuleException(
                    ErrorCode.INVALID_CURSOR, "Pagination cursor must not be blank");
        }
        try {
            byte[] decodedBytes = Base64.getUrlDecoder().decode(cursor.trim());
            String decoded = new String(decodedBytes, StandardCharsets.UTF_8);
            int sep = decoded.indexOf('|');
            if (sep <= 0 || sep == decoded.length() - 1 || decoded.indexOf('|', sep + 1) != -1) {
                throw new BusinessRuleException(
                        ErrorCode.INVALID_CURSOR,
                        "Invalid pagination cursor format; expected encoded 'createdAt|id'");
            }
            Instant createdAt = Instant.parse(decoded.substring(0, sep));
            long id = Long.parseLong(decoded.substring(sep + 1));
            if (id <= 0L) {
                throw new BusinessRuleException(
                        ErrorCode.INVALID_CURSOR, "Cursor transaction ID must be positive");
            }
            return new DecodedCursor(createdAt, id);
        } catch (BusinessRuleException ex) {
            throw ex;
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new BusinessRuleException(
                    ErrorCode.INVALID_CURSOR, "Invalid or tampered pagination cursor");
        }
    }

    /** Decoded composite keyset coordinates {@code (createdAt, id)}. */
    public record DecodedCursor(Instant createdAt, Long id) {}
}
