package com.example.aipractice.rag;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class KeywordSearchRepository {

    private final JdbcTemplate jdbcTemplate;

    public KeywordSearchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SearchHit> search(SearchQuery query, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id::text AS id,
                       content,
                       metadata->>'source' AS source,
                       metadata->>'title' AS title,
                       metadata->>'category' AS category,
                       metadata->>'page_number' AS page_number,
                       metadata->>'chunk_index' AS chunk_index,
                       metadata->>'chunk_start' AS chunk_start,
                       metadata->>'chunk_end' AS chunk_end,
                       metadata->>'profile' AS profile,
                       scores.keyword_score
                FROM vector_store
                CROSS JOIN LATERAL (
                    SELECT coalesce(
                        sum(CASE
                            WHEN lower(coalesce(content, '')) LIKE '%' || token || '%'
                            THEN 1.0 ELSE 0.0
                        END) / nullif(count(*), 0),
                        0.0
                    ) AS keyword_score
                    FROM unnest(
                        regexp_split_to_array(lower(?), '[[:space:]]+')
                    ) AS token
                    WHERE length(token) >= 2
                ) scores
                WHERE scores.keyword_score > 0
                """);

        List<Object> parameters = new ArrayList<>();
        parameters.add(query.question());
        addMetadataFilter(sql, parameters, "title", query.title());
        addMetadataFilter(sql, parameters, "category", query.category());
        addMetadataFilter(sql, parameters, "profile", query.profile());
        sql.append(" ORDER BY keyword_score DESC, id LIMIT ?");
        parameters.add(limit);

        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> {
                    double keywordScore = rs.getDouble("keyword_score");
                    return new SearchHit(
                            rs.getString("id"),
                            rs.getString("content"),
                            rs.getString("source"),
                            rs.getString("title"),
                            rs.getString("category"),
                            rs.getString("page_number"),
                            nullableInteger(rs.getString("chunk_index")),
                            nullableInteger(rs.getString("chunk_start")),
                            nullableInteger(rs.getString("chunk_end")),
                            rs.getString("profile"),
                            keywordScore,
                            null,
                            keywordScore
                    );
                },
                parameters.toArray()
        );
    }

    private void addMetadataFilter(
            StringBuilder sql,
            List<Object> parameters,
            String key,
            String value) {

        if (value != null) {
            sql.append(" AND metadata->>'").append(key).append("' = ?");
            parameters.add(value);
        }
    }

    private Integer nullableInteger(String value) {
        return value == null ? null : Integer.valueOf(value);
    }
}
