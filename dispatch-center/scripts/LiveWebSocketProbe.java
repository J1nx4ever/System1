import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Minimal JDK-only probe used by the acceptance procedure. */
public final class LiveWebSocketProbe implements WebSocket.Listener {
    private final CountDownLatch messages;
    private final StringBuilder partial = new StringBuilder();

    private LiveWebSocketProbe(int expectedMessages) {
        messages = new CountDownLatch(expectedMessages);
    }

    public static void main(String[] args) throws Exception {
        URI uri = URI.create(args.length > 0 ? args[0]
                : "ws://localhost:8080/ws/state?simulationId=SIM_001");
        int expected = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        LiveWebSocketProbe listener = new LiveWebSocketProbe(expected);
        WebSocket socket = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .newWebSocketBuilder().buildAsync(uri, listener).get(5, TimeUnit.SECONDS);
        if (!listener.messages.await(15, TimeUnit.SECONDS)) {
            socket.abort();
            throw new IllegalStateException("Timed out waiting for " + expected + " messages");
        }
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "probe complete").get(5, TimeUnit.SECONDS);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String message = partial.toString();
            String type = message.replaceFirst(".*\\\"type\\\":\\\"([^\\\"]+)\\\".*", "$1");
            String sequence = message.replaceFirst(".*\\\"sequence\\\":([0-9]+).*", "$1");
            System.out.println("WS type=" + type + " sequence=" + sequence + " bytes=" + message.length());
            partial.setLength(0);
            messages.countDown();
        }
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }
}
