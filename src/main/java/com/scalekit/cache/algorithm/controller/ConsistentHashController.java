package com.scalekit.cache.algorithm.controller;

import com.scalekit.cache.dto.HotspotReport;
import com.scalekit.cache.dto.NodeAssignment;
import com.scalekit.cache.dto.RebalanceReport;
import com.scalekit.cache.dto.RingPosition;
import com.scalekit.cache.algorithm.service.ConsistentHashService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST controller exposing consistent‑hash functionality.
 *
 * Base path: {@code /api/consistent-hash}
 */
@RestController
@RequestMapping("/api/consistent-hash")
@RequiredArgsConstructor
public class ConsistentHashController {

    private final ConsistentHashService service;

    /** Add a new physical node */
    @PostMapping("/nodes/{nodeName}")
    public ResponseEntity<Void> addNode(@PathVariable String nodeName) {
        service.addNode(nodeName);
        return ResponseEntity.ok().build();
    }

    /** Remove an existing node */
    @DeleteMapping("/nodes/{nodeName}")
    public ResponseEntity<Void> removeNode(@PathVariable String nodeName) {
        service.removeNode(nodeName);
        return ResponseEntity.ok().build();
    }

    /** Get the node responsible for a single key */
    @GetMapping("/node")
    public ResponseEntity<NodeAssignment> getNode(@RequestParam String key) {
        NodeAssignment assignment = service.getNodeForKey(key);
        return ResponseEntity.ok(assignment);
    }

    /** Get replicated nodes for a key */
    @GetMapping("/nodes")
    public ResponseEntity<List<String>> getNodes(@RequestParam String key,
                                                 @RequestParam(defaultValue = "3") int replication) {
        List<String> nodes = service.getNodesForKey(key, replication);
        return ResponseEntity.ok(nodes);
    }

    /** Get a snapshot of the entire ring (virtual node positions) */
    @GetMapping("/ring")
    public ResponseEntity<List<RingPosition>> getRingSnapshot() {
        return ResponseEntity.ok(service.getRingSnapshot());
    }

    /** Detect hotspots */
    @GetMapping("/hotspots")
    public ResponseEntity<List<HotspotReport>> getHotspots(@RequestParam(defaultValue = "1.5") double threshold) {
        return ResponseEntity.ok(service.detectHotspots(threshold));
    }

    /** Rebalance analysis */
    @GetMapping("/rebalance")
    public ResponseEntity<RebalanceReport> getRebalanceReport() {
        return ResponseEntity.ok(service.rebalanceCheck());
    }

    /** Get all physical node names */
    @GetMapping("/all-nodes")
    public ResponseEntity<Set<String>> getAllNodes() {
        return ResponseEntity.ok(service.getAllNodes());
    }

    /** Get key distribution for a list of keys (for debugging) */
    @PostMapping("/distribution")
    public ResponseEntity<Map<String, Long>> getKeyDistribution(@RequestBody List<String> keys) {
        return ResponseEntity.ok(service.getKeyDistribution(keys));
    }
}
