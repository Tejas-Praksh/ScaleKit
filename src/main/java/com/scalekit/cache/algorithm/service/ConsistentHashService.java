package com.scalekit.cache.algorithm.service;

import com.scalekit.cache.algorithm.ConsistentHashRing;
import com.scalekit.cache.dto.HotspotReport;
import com.scalekit.cache.dto.NodeAssignment;
import com.scalekit.cache.dto.RebalanceReport;
import com.scalekit.cache.dto.RingPosition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service layer exposing consistent‑hash ring functionality.
 *
 * <p>All public methods delegate to {@link ConsistentHashRing} and convert the
 * raw results into DTOs that are safe for external consumption (e.g. REST API).
 */
@Service
@RequiredArgsConstructor
public class ConsistentHashService {

    private final ConsistentHashRing ring;

    /** Add a physical node to the ring. */
    public void addNode(String nodeName) {
        ring.addNode(nodeName);
    }

    /** Remove a node from the ring. */
    public void removeNode(String nodeName) {
        ring.removeNode(nodeName);
    }

    /** Lookup the node responsible for a single key. */
    public NodeAssignment getNodeForKey(String key) {
        return ring.getNodeForKey(key);
    }

    /** Lookup multiple nodes for a key according to a replication factor. */
    public List<String> getNodesForKey(String key, int replicationFactor) {
        return ring.getNodes(key, replicationFactor);
    }

    /** Get a snapshot of the ring (all virtual node positions). */
    public List<RingPosition> getRingSnapshot() {
        return ring.getRingSnapshot();
    }

    /** Detect hotspots using the supplied threshold multiplier. */
    public List<HotspotReport> detectHotspots(double thresholdMultiplier) {
        return ring.detectHotspots(thresholdMultiplier);
    }

    /** Perform a rebalance analysis report. */
    public RebalanceReport rebalanceCheck() {
        return ring.rebalanceCheck();
    }

    /** Retrieve all physical node names currently in the ring. */
    public Set<String> getAllNodes() {
        return ring.getAllNodes();
    }

    /** Get key distribution statistics for a list of keys. */
    public Map<String, Long> getKeyDistribution(List<String> keys) {
        return ring.getKeyDistribution(keys);
    }
}
