import mine.field.v1.MapState;
import mine.field.v1.RoadNode;

public final class SmokeTest {
  public static void main(String[] args) throws Exception {
    MapState original = MapState.newBuilder()
        .setSimulationId("SIM_001")
        .setSequence(1L)
        .setSimTimeSeconds(0.5)
        .setImageFile("image2.png")
        .setImageWidthPx(1920)
        .setImageHeightPx(957)
        .addNodes(RoadNode.newBuilder().setNodeId("Crusher").setX(450).setY(360))
        .build();
    MapState decoded = MapState.parseFrom(original.toByteArray());
    if (!original.equals(decoded) || decoded.getNodesCount() != 1) {
      throw new AssertionError("protobuf round-trip failed");
    }
    System.out.println("OK " + decoded.getSimulationId() + " nodes=" + decoded.getNodesCount());
  }
}
