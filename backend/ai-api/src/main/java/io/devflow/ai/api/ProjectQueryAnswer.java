package io.devflow.ai.api;

/**
 * Answer to a project question, grounded only in the project's board data (PRODUCT_SPEC.md Section 5.2b).
 *
 * @param answer      plain text; says there is no information when the data does not cover the question
 * @param totalTasks  tasks in the project; the summary counts given to the model always cover all of them
 * @param tasksListed tasks listed one by one in the model's context; fewer than {@code totalTasks} when the
 *                    project is larger than the context budget (finished tasks are left out first)
 */
public record ProjectQueryAnswer(String answer, int totalTasks, int tasksListed) {
}
