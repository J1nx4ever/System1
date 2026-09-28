package com.mine.dispatch.capability.decision;

import Fusion.grpc.ConflictVehicleInfo;
import Fusion.grpc.Observation;
import Fusion.grpc.ObservationDict;
import Fusion.grpc.StateData;

import java.util.*;

public final class SpeedGraphObservationBuilder {
    public static final int NODE_DIM = 17;
    public static final int EDGE_DIM = 4;
    public static final int MAX_NODES = 15;
    public static final int MAX_EDGES = 128;

    private static final Map<Integer, Integer> ROAD_TYPES = Map.of(
            1, 0, 2, 0, 3, 0, 4, 1, 5, 1, 6, 1, 7, 1, 8, 1, 9, 1);

    public Batch build(ObservationDict request) {
        int batchSize = request.getObservationsCount();
        if (batchSize == 0) throw new IllegalArgumentException("speed observation batch is empty");
        float[] nodes = new float[batchSize * MAX_NODES * NODE_DIM];
        float[] edges = new float[batchSize * MAX_EDGES * EDGE_DIM];
        long[] indices = new long[batchSize * MAX_EDGES * 2];
        Arrays.fill(indices, -1L);
        float[] nodeMask = new float[batchSize * MAX_NODES];
        float[] edgeMask = new float[batchSize * MAX_EDGES];

        for (int batch = 0; batch < batchSize; batch++) {
            Observation observation = request.getObservations(batch);
            if (!observation.hasState()) {
                throw new IllegalArgumentException("speed observation state missing: " + observation.getAgentId());
            }
            Graph graph = buildLocal(observation.getState());
            if (graph.nodes.size() > MAX_NODES || graph.edges.size() > MAX_EDGES) {
                throw new IllegalArgumentException("graph exceeds checkpoint tensor bounds: nodes="
                        + graph.nodes.size() + "/" + MAX_NODES + ", edges="
                        + graph.edges.size() + "/" + MAX_EDGES);
            }
            for (int n = 0; n < graph.nodes.size(); n++) {
                System.arraycopy(graph.nodes.get(n), 0, nodes,
                        (batch * MAX_NODES + n) * NODE_DIM, NODE_DIM);
                nodeMask[batch * MAX_NODES + n] = 1f;
            }
            for (int e = 0; e < graph.edges.size(); e++) {
                System.arraycopy(graph.edges.get(e), 0, edges,
                        (batch * MAX_EDGES + e) * EDGE_DIM, EDGE_DIM);
                long[] pair = graph.indices.get(e);
                int offset = (batch * MAX_EDGES + e) * 2;
                indices[offset] = pair[0];
                indices[offset + 1] = pair[1];
                edgeMask[batch * MAX_EDGES + e] = 1f;
            }
        }
        return new Batch(batchSize, nodes, edges, indices, nodeMask, edgeMask);
    }

    private Graph buildLocal(StateData state) {
        Graph graph = new Graph();
        Map<Integer, Integer> vehicles = new HashMap<>();
        Map<Integer, Integer> roads = new HashMap<>();
        int egoId = state.getEgoAgentId();
        vehicles.put(egoId, 0);
        graph.nodes.add(features(state, egoId, true));

        int targetRoad = state.getTargetRoadNode();
        if (targetRoad > 0) {
            int roadIndex = graph.nodes.size();
            roads.put(targetRoad, roadIndex);
            graph.nodes.add(roadFeatures(targetRoad));
            addEdge(graph, 0, roadIndex, 0f, finite(state.getDistToInt()),
                    finite(state.getTtaEgo()), 0f);
        }

        if (state.getHasFront()) {
            int frontId = state.getFrontAgentId();
            int frontIndex = graph.nodes.size();
            vehicles.put(frontId, frontIndex);
            graph.nodes.add(vehicleFeatures(state.getFrontSpeed(), 0, frontId, false,
                    false, 0, 0, false, 0, 0, false, 0, 0, false, 0, 0));
            addEdge(graph, 0, frontIndex, 1f, finite(state.getFrontDist()),
                    finite(state.getFrontTtc()),
                    finite(state.getFrontSpeed()) - finite(state.getEgoSpeed()));
        }

        if (state.getHasRear()) {
            int rearId = state.getRearAgentId();
            int rearIndex = vehicles.computeIfAbsent(rearId, ignored -> {
                graph.nodes.add(vehicleFeatures(state.getRearSpeed(), 0, rearId, false,
                        false, 0, 0, false, 0, 0, false, 0, 0, false, 0, 0));
                return graph.nodes.size() - 1;
            });
            addEdge(graph, rearIndex, 0, 1f, finite(state.getRearDist()), 0f,
                    finite(state.getEgoSpeed()) - finite(state.getRearSpeed()));
        }

        List<ConflictNode> conflicts = new ArrayList<>();
        if (state.getHasConflict()) {
            for (ConflictVehicleInfo conflict : state.getConflictVehiclesList()) {
                int id = conflict.getAgentId();
                int index = vehicles.computeIfAbsent(id, ignored -> {
                    graph.nodes.add(vehicleFeatures(conflict.getConflictSpeed(), 0, id, false,
                            false, 0, 0, false, 0, 0, false, 0, 0,
                            false, conflict.getDiffEta(), 0));
                    return graph.nodes.size() - 1;
                });
                float eta = finite(conflict.getConflictEta());
                float speed = finite(conflict.getConflictSpeed());
                conflicts.add(new ConflictNode(index, eta, speed));
                float signedEta = finite(state.getTtaEgo()) - eta;
                addEdge(graph, 0, index, 2f, signedEta, Math.abs(signedEta),
                        finite(conflict.getDiffDist()));
                Integer roadIndex = roads.get(targetRoad);
                if (roadIndex != null) addEdge(graph, index, roadIndex, 0f, 0f, eta, 0f);
            }
            for (int i = 0; i < conflicts.size(); i++) {
                for (int j = 0; j < conflicts.size(); j++) {
                    if (i == j) continue;
                    ConflictNode source = conflicts.get(i);
                    ConflictNode target = conflicts.get(j);
                    float etaDelta = source.eta - target.eta;
                    addEdge(graph, source.index, target.index, 2f, etaDelta,
                            Math.abs(etaDelta), source.speed - target.speed);
                }
            }
        }
        return graph;
    }

    private static float[] features(StateData state, int agentId, boolean ego) {
        float nearestEta = 0f;
        if (state.getConflictVehiclesCount() > 0) {
            nearestEta = Float.POSITIVE_INFINITY;
            for (ConflictVehicleInfo item : state.getConflictVehiclesList()) {
                nearestEta = Math.min(nearestEta, Math.abs(finite(item.getDiffEta())));
            }
        }
        float distance = finite(state.getDistToInt());
        return vehicleFeatures(state.getEgoSpeed(), state.getPrevCmdAccel(), agentId, ego,
                false, distance, state.getTtaEgo(), state.getHasFront(), state.getFrontDist(),
                state.getFrontTtc(), state.getHasRear(), state.getRearSpeed(), state.getRearDist(),
                state.getHasConflict(), nearestEta, distance);
    }

    private static float[] vehicleFeatures(double speed, double previousAcceleration, int agentId,
            boolean ego, boolean terminal, double distance, double tta, boolean hasFront,
            double frontDistance, double frontTtc, boolean hasRear, double rearSpeed,
            double rearDistance, boolean hasConflict, double etaDifference, double remainingPath) {
        return new float[]{
                1f, bounded(speed, -50, 50), bounded(previousAcceleration, -10, 10),
                ego ? 1f : 0f, terminal ? 1f : 0f, bounded(agentId, 0, 1000) / 20f,
                bounded(distance, -1000, 1000), bounded(tta, -1000, 1000),
                hasFront ? 1f : 0f, bounded(frontDistance, -1000, 1000),
                bounded(frontTtc, -1000, 1000), hasRear ? 1f : 0f,
                bounded(rearSpeed, -1000, 1000), bounded(rearDistance, -1000, 1000),
                hasConflict ? 1f : 0f, bounded(etaDifference, -1000, 1000),
                bounded(remainingPath, -1000, 1000)
        };
    }

    private static float[] roadFeatures(int roadId) {
        return new float[]{0f, roadId <= 3 && roadId >= 1 ? 1f : 0f,
                ROAD_TYPES.getOrDefault(roadId, 0), 0f, 0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
    }

    private static void addEdge(Graph graph, int source, int target,
            float type, float first, float second, float third) {
        graph.edges.add(new float[]{type, finite(first), finite(second), finite(third)});
        graph.indices.add(new long[]{source, target});
    }

    private static float bounded(double value, double low, double high) {
        float clean = finite(value);
        return (float) Math.max(low, Math.min(high, clean));
    }

    private static float finite(double value) {
        return Double.isFinite(value) ? (float) value : 0f;
    }

    private static final class Graph {
        private final List<float[]> nodes = new ArrayList<>();
        private final List<float[]> edges = new ArrayList<>();
        private final List<long[]> indices = new ArrayList<>();
    }

    private record ConflictNode(int index, float eta, float speed) {}

    public record Batch(int size, float[] nodes, float[] edges, long[] edgeIndices,
                        float[] nodeMask, float[] edgeMask) {}
}
