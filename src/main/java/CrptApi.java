import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class CrptApi {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Semaphore semaphore;
    private final ScheduledExecutorService scheduler;
    private final long intervalMillis;

    private final AtomicLong lastRequestTime;
    private final AtomicInteger requestCount;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.semaphore = new Semaphore(requestLimit);
        this.scheduler = Executors.newScheduledThreadPool(1);

        this.intervalMillis = timeUnit.toMillis(1);
        this.lastRequestTime = new AtomicLong(System.currentTimeMillis());
        this.requestCount = new AtomicInteger(0);

        startResetTask(timeUnit, requestLimit);
    }

    private void startResetTask(TimeUnit timeUnit, int requestLimit) {
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            if (now - lastRequestTime.get() >= intervalMillis) {
                semaphore.release(requestLimit - semaphore.availablePermits());
                lastRequestTime.set(now);
                requestCount.set(0);
            }
        }, 0, intervalMillis, timeUnit);
    }

    public void createDocument(Object document) throws IOException, InterruptedException {
        semaphore.acquire();
        try {
            String jsonBody = objectMapper.writeValueAsString(document);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200 || response.statusCode() != 201) {
                throw new IOException("Failed to create document: " + response.body());
            }
        } finally {
            semaphore.release();
        }
    }

    public static void main(String[] args) {
        try {
            CrptApi api = new CrptApi(TimeUnit.MINUTES, 10);

            ObjectNode document = api.objectMapper.createObjectNode();
            document.put("doc_id", "123");
            document.put("doc_status", "NEW");

            api.createDocument(document);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

