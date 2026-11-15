package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

interface AgentProject {
    String name();
    String defaultProvider();
    JsonNode inspect(String action, ObjectNode args) throws IOException, InterruptedException;
    void checkDestination(UdtDraft draft) throws IOException, InterruptedException;
    ObjectNode createUdt(UdtDraft draft) throws IOException, InterruptedException;
    default void showUdt(UdtDraft draft) throws IOException {}
}
