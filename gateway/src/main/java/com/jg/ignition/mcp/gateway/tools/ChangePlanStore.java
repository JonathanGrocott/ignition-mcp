package com.jg.ignition.mcp.gateway.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceSignature;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One bounded store per module registry. Restart invalidates plans; nothing is written to disk. */
final class ChangePlanStore {
    static final long TTL_MS = 600_000;
    private final Clock clock;
    private final int maxPlans;
    private final long maxBytes;
    private final Map<String, Plan> plans = new HashMap<>();

    record Change(ResourcePath path, Resource desired, String beforeHash, String afterHash,
                  ResourceSignature signature, String action) {}

    static final class Plan {
        final String id;
        final String owner;
        final String project;
        final String hash;
        final long expiresAt;
        final List<Change> changes;
        final ObjectNode summary;
        final int materialBytes;
        volatile String state = "pending";
        ObjectNode receipt;
        Plan(String owner, String project, String hash, long expiresAt, List<Change> changes, ObjectNode summary, int bytes) {
            this.id = UUID.randomUUID().toString(); this.owner = owner; this.project = project; this.hash = hash;
            this.expiresAt = expiresAt; this.changes = List.copyOf(changes); this.summary = summary.deepCopy();
            this.materialBytes = bytes;
        }
        ObjectNode snapshot() {
            ObjectNode result = receipt == null ? summary.deepCopy() : receipt.deepCopy();
            result.put("planId", id).put("planHash", hash).put("state", state).put("expiresAt", expiresAt);
            return result;
        }
    }

    ChangePlanStore() { this(Clock.systemUTC(), 32, 16_777_216); }
    ChangePlanStore(Clock clock, int maxPlans, long maxBytes) { this.clock = clock; this.maxPlans = maxPlans; this.maxBytes = maxBytes; }

    synchronized Plan create(String owner, String project, String hash, List<Change> changes, ObjectNode summary, int bytes) {
        purge();
        if (plans.size() >= maxPlans || plans.values().stream().mapToLong(plan -> plan.materialBytes).sum() + bytes > maxBytes) {
            throw new InspectionSupport.InspectionException("PLAN_CAPACITY", "Pending plan storage is full; wait for plans to expire");
        }
        Plan plan = new Plan(owner, project, hash, clock.millis() + TTL_MS, changes, summary, bytes);
        plans.put(plan.id, plan);
        return plan;
    }

    synchronized Plan owned(String id, String owner) {
        purge();
        Plan plan = plans.get(id);
        if (plan == null || !plan.owner.equals(owner)) {
            throw new InspectionSupport.InspectionException("NOT_FOUND", "Plan not found, expired or belongs to another token");
        }
        return plan;
    }

    boolean expired(Plan plan) { return clock.millis() >= plan.expiresAt; }

    private void purge() {
        plans.values().removeIf(plan -> plan.expiresAt <= clock.millis() && !plan.state.equals("applying"));
    }
}
