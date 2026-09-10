package com.enterprise.copilot.knowledge;

/**
 * One passage the retriever selected for a question, already numbered for citation.
 *
 * <p>{@code index} is 1-based and is the marker the model is told to cite ("[1]"), so the number
 * the user sees in the answer always resolves to a real source.
 */
public record RetrievedSnippet(
    int index,
    String docId,
    String title,
    String source,
    String snippet,
    int chunkIndex,
    double score) {}
