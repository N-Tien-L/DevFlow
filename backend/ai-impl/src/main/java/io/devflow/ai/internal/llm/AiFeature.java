package io.devflow.ai.internal.llm;

/** The AI capability issuing an LLM call; a bounded set so it is safe as a metric tag. */
public enum AiFeature {

    PROJECT_QA("project_qa"),
    TASK_BREAKDOWN("breakdown"),
    CI_SUMMARY("ci_summary");

    private final String tag;

    AiFeature(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
