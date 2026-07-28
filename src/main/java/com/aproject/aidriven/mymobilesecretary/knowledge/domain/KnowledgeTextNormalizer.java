package com.aproject.aidriven.mymobilesecretary.knowledge.domain;

import java.text.Normalizer;
import java.util.Locale;

/** Shared deterministic normalization for bounded personal-knowledge subjects. */
public final class KnowledgeTextNormalizer {

    private KnowledgeTextNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Z}\\p{P}\\p{S}]+", "")
                .strip();
    }
}
