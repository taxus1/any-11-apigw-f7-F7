package com.apigw.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 转发链路测试用的真实上游（JDK 自带 HttpServer，起在随机端口，不依赖外部进程）。
 *
 * 默认行为：回显收到的方法、路径、查询串与请求头（JSON），并允许测试代码预设
 * 响应状态码、响应头、响应体，以及模拟「读了请求但迟迟不回」的超时场景。
 */
class FakeUpstream implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<HttpExchange> lastExchange = new AtomicReference<>();
    /** 正在「故意不响应」的处理线程，close 时打断它们，避免非守护线程把 JVM 拖住。 */
    private final CopyOnWriteArrayList<Thread> hanging = new CopyOnWriteArrayList<>();
    private volatile int responseStatus = 200;
    private volatile String customBody;
    private volatile boolean hangForever;
    /** 每次请求自增的命中次数（重试会打多发，用它断言到底打了上游几次）。 */
    private final java.util.concurrent.atomic.AtomicInteger hitCount =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 可选：按「第几次命中」脚本化状态码（1 起）；命中次数超出列表后回落 responseStatus。 */
    private volatile java.util.List<Integer> scriptedStatuses = java.util.List.of();

    FakeUpstream() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        // 用守护线程池，即便有 handler 卡住也不阻止 JVM 退出
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-upstream");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    HttpExchange lastExchange() {
        return lastExchange.get();
    }

    void setResponseStatus(int status) {
        this.responseStatus = status;
    }

    void setCustomBody(String body) {
        this.customBody = body;
    }

    void setHangForever(boolean hang) {
        this.hangForever = hang;
    }

    int hitCount() {
        return hitCount.get();
    }

    /** 按命中次序脚本化状态码：如 [500,500,200] 表示前两次 500、第三次起 200。 */
    void scriptStatuses(Integer... statuses) {
        this.scriptedStatuses = java.util.List.of(statuses);
    }

    private void handle(HttpExchange exchange) {
        lastExchange.set(exchange);
        int hit = hitCount.incrementAndGet();
        if (hangForever) {
            // 读到请求后永不响应，逼网关走响应超时分支；可中断，close 时会被打断
            Thread t = Thread.currentThread();
            hanging.add(t);
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                hanging.remove(t);
                exchange.close();
            }
            return;
        }

        try {
            String headers = exchange.getRequestHeaders().entrySet().stream()
                    .map(e -> "\"" + e.getKey() + "\":\""
                            + String.join(",", e.getValue()).replace("\"", "'") + "\"")
                    .reduce((a, b) -> a + "," + b).orElse("");
            String body = customBody != null ? customBody
                    : "{\"method\":\"" + exchange.getRequestMethod()
                            + "\",\"path\":\"" + exchange.getRequestURI().getPath()
                            + "\",\"query\":\"" + (exchange.getRequestURI().getRawQuery() == null
                                    ? "" : exchange.getRequestURI().getRawQuery())
                            + "\",\"headers\":{" + headers + "}}";
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            int status = scriptedStatuses.isEmpty() ? responseStatus
                    : (hit <= scriptedStatuses.size()
                            ? scriptedStatuses.get(hit - 1) : responseStatus);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        } catch (IOException e) {
            exchange.close();
        }
    }

    @Override
    public void close() {
        hanging.forEach(Thread::interrupt);
        server.stop(0);
    }
}
