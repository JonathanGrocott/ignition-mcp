package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Set;

interface AgentProject {
    String name();
    String defaultProvider();
    /** Running Gateway version such as 8.3.10, or empty when it cannot be determined. */
    default String ignitionVersion() { return ""; }
    default ObjectNode designerContext() { return AssistantJson.JSON.createObjectNode(); }
    JsonNode inspect(String action, ObjectNode args) throws IOException, InterruptedException;
    void checkDestination(UdtDraft draft) throws IOException, InterruptedException;
    ObjectNode createUdt(UdtDraft draft) throws IOException, InterruptedException;
    default void checkProjectChanges(Set<String> paths) throws IOException, InterruptedException {}
    default boolean requestProjectUpdate() { return false; }
    default void showUdt(UdtDraft draft) throws IOException {}
}
