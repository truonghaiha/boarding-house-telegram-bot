package com.boardinghousebot.bot.chatbot;

import java.util.Objects;

/**
 * Ngữ cảnh câu hỏi sau khi được chuẩn hóa và phân tích bởi Chatbot Engine.
 */
public class QueryContext {

    private final String rawQuery;
    private final String normalizedQuery;
    private final ChatbotIntent intent;
    private final TargetPeriod targetPeriod;

    public QueryContext(String rawQuery, String normalizedQuery, ChatbotIntent intent, TargetPeriod targetPeriod) {
        this.rawQuery = rawQuery != null ? rawQuery.trim() : "";
        this.normalizedQuery = normalizedQuery != null ? normalizedQuery.trim() : "";
        this.intent = intent != null ? intent : ChatbotIntent.UNKNOWN;
        this.targetPeriod = targetPeriod != null ? targetPeriod : TargetPeriod.latestRecorded();
    }

    public String getRawQuery() {
        return rawQuery;
    }

    public String getNormalizedQuery() {
        return normalizedQuery;
    }

    public ChatbotIntent getIntent() {
        return intent;
    }

    public TargetPeriod getTargetPeriod() {
        return targetPeriod;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        QueryContext that = (QueryContext) o;
        return Objects.equals(rawQuery, that.rawQuery) &&
               Objects.equals(normalizedQuery, that.normalizedQuery) &&
               intent == that.intent &&
               Objects.equals(targetPeriod, that.targetPeriod);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawQuery, normalizedQuery, intent, targetPeriod);
    }

    @Override
    public String toString() {
        return "QueryContext{" +
                "rawQuery='" + rawQuery + '\'' +
                ", intent=" + intent +
                ", targetPeriod=" + targetPeriod +
                '}';
    }
}
