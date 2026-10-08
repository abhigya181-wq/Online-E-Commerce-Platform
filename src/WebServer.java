import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class WebServer {
    private static final UserDAO userDAO = new UserDAO();
    private static final ProductDAO productDAO = new ProductDAO();
    private static final OrderDAO orderDAO = new OrderDAO();

    public static void main(String[] args) throws IOException {
        DBConnection.createTables();

        int port = 8080;
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.trim().isEmpty()) {
            try {
                port = Integer.parseInt(portEnv.trim());
            } catch (NumberFormatException e) {
                System.err.println("Invalid PORT env var, falling back to 8080: " + e.getMessage());
            }
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Web UI router
        server.createContext("/", new StaticPageHandler());

        // Health check endpoints (essential for Render health check monitoring)
        HealthHandler healthHandler = new HealthHandler();
        server.createContext("/health", healthHandler);
        server.createContext("/api/health", healthHandler);

        // API endpoints
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/register", new RegisterHandler());
        server.createContext("/api/products", new ProductHandler());
        server.createContext("/api/orders", new OrderHandler());
        server.createContext("/api/users", new UserHandler());

        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down WebServer...");
            server.stop(1);
        }));

        System.out.println("======================================================");
        System.out.println("🚀 E-Commerce Platform Server is LIVE!");
        System.out.println("🌐 URL: http://localhost:" + port);
        System.out.println("🩺 Health Check: http://localhost:" + port + "/health");
        System.out.println("======================================================");
    }

    private static Map<String, String> parseFormData(String body) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isEmpty()) return map;
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8.name());
            String val = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name()) : "";
            map.put(key, val);
        }
        return map;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = is.read(buffer)) != -1) {
            bos.write(buffer, 0, read);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response, String contentType) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // Handles Health check monitoring for Render / cloud load balancers
    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 204, "", "application/json");
                return;
            }
            String json = String.format("{\"status\":\"UP\",\"timestamp\":\"%s\",\"service\":\"ecommerce-platform\"}",
                    java.time.Instant.now().toString());
            sendResponse(exchange, 200, json, "application/json");
        }
    }

    // Handles User Authentication
    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> data = parseFormData(readBody(exchange));
                try {
                    User u = userDAO.login(data.get("email"), data.get("password"));
                    if (u != null) {
                        String json = String.format("{\"success\":true,\"user\":{\"id\":%d,\"name\":\"%s\",\"email\":\"%s\",\"role\":\"%s\"}}",
                                u.getId(), escapeJson(u.getName()), escapeJson(u.getEmail()), u.getRole());
                        sendResponse(exchange, 200, json, "application/json");
                        return;
                    }
                    sendResponse(exchange, 401, "{\"success\":false,\"message\":\"Invalid email or password\"}", "application/json");
                } catch (Exception e) {
                    sendResponse(exchange, 500, "{\"success\":false,\"message\":\"" + escapeJson(e.getMessage()) + "\"}", "application/json");
                }
            } else {
                sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
            }
        }
    }

    // Handles User Registration
    static class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> data = parseFormData(readBody(exchange));
                try {
                    String name = data.get("name");
                    String email = data.get("email");
                    String pass = data.get("password");
                    String role = data.get("role");
                    userDAO.add(User.create(role, 0, name, email, pass));
                    sendResponse(exchange, 200, "{\"success\":true,\"message\":\"Registration successful!\"}", "application/json");
                } catch (Exception e) {
                    sendResponse(exchange, 400, "{\"success\":false,\"message\":\"" + escapeJson(e.getMessage()) + "\"}", "application/json");
                }
            }
        }
    }

    // Handles Product operations (CRUD + Search)
    static class ProductHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            try {
                if ("GET".equalsIgnoreCase(method)) {
                    String query = exchange.getRequestURI().getQuery();
                    Map<String, String> params = parseQueryParams(query);
                    List<Product> list;
                    if (params.containsKey("seller_id")) {
                        list = productDAO.getBySeller(Integer.parseInt(params.get("seller_id")));
                    } else if (params.containsKey("search") && !params.get("search").trim().isEmpty()) {
                        list = productDAO.search(params.get("search").trim());
                    } else {
                        list = productDAO.getAll();
                    }

                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < list.size(); i++) {
                        Product p = list.get(i);
                        sb.append(String.format("{\"id\":%d,\"sellerId\":%d,\"name\":\"%s\",\"price\":%.2f,\"stock\":%d}",
                                p.getId(), p.getSellerId(), escapeJson(p.getName()), p.getPrice(), p.getStock()));
                        if (i < list.size() - 1) sb.append(",");
                    }
                    sb.append("]");
                    sendResponse(exchange, 200, sb.toString(), "application/json");
                } else if ("POST".equalsIgnoreCase(method)) {
                    Map<String, String> data = parseFormData(readBody(exchange));
                    String action = data.getOrDefault("action", "add");
                    if ("add".equalsIgnoreCase(action)) {
                        String name = data.get("name");
                        if (name == null || name.trim().isEmpty()) {
                            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"Product name is required.\"}", "application/json");
                            return;
                        }
                        double price = Double.parseDouble(data.get("price"));
                        int stock = Integer.parseInt(data.get("stock"));
                        if (price < 0 || stock < 0) {
                            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"Price and stock cannot be negative.\"}", "application/json");
                            return;
                        }
                        productDAO.add(new Product(0, Integer.parseInt(data.get("seller_id")),
                                name.trim(), price, stock));
                        sendResponse(exchange, 200, "{\"success\":true}", "application/json");
                    } else if ("updateStock".equalsIgnoreCase(action)) {
                        int stock = Integer.parseInt(data.get("stock"));
                        if (stock < 0) {
                            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"Stock cannot be negative.\"}", "application/json");
                            return;
                        }
                        productDAO.updateStock(Integer.parseInt(data.get("id")), stock);
                        sendResponse(exchange, 200, "{\"success\":true}", "application/json");
                    } else if ("delete".equalsIgnoreCase(action)) {
                        productDAO.delete(Integer.parseInt(data.get("id")));
                        sendResponse(exchange, 200, "{\"success\":true}", "application/json");
                    }
                }
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"success\":false,\"message\":\"" + escapeJson(e.getMessage()) + "\"}", "application/json");
            }
        }
    }

    // Handles Order operations
    static class OrderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            try {
                if ("GET".equalsIgnoreCase(method)) {
                    String query = exchange.getRequestURI().getQuery();
                    Map<String, String> params = parseQueryParams(query);
                    List<Object[]> rows;
                    if (params.containsKey("buyer_id")) {
                        rows = orderDAO.getByBuyer(Integer.parseInt(params.get("buyer_id")));
                    } else if (params.containsKey("seller_id")) {
                        rows = orderDAO.getBySeller(Integer.parseInt(params.get("seller_id")));
                    } else {
                        rows = orderDAO.getAll();
                    }

                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < rows.size(); i++) {
                        Object[] r = rows.get(i);
                        sb.append(String.format("{\"id\":%d,\"buyer\":\"%s\",\"product\":\"%s\",\"qty\":%d,\"total\":%.2f,\"status\":\"%s\"}",
                                (int) r[0], escapeJson((String) r[1]), escapeJson((String) r[2]), (int) r[3], (double) r[4], escapeJson((String) r[5])));
                        if (i < rows.size() - 1) sb.append(",");
                    }
                    sb.append("]");
                    sendResponse(exchange, 200, sb.toString(), "application/json");
                } else if ("POST".equalsIgnoreCase(method)) {
                    Map<String, String> data = parseFormData(readBody(exchange));
                    String action = data.getOrDefault("action", "place");
                    if ("place".equalsIgnoreCase(action)) {
                        try {
                            orderDAO.placeOrder(Integer.parseInt(data.get("buyer_id")),
                                    Integer.parseInt(data.get("product_id")),
                                    Integer.parseInt(data.get("quantity")));
                            sendResponse(exchange, 200, "{\"success\":true,\"message\":\"Order placed successfully!\"}", "application/json");
                        } catch (StockException se) {
                            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"" + escapeJson(se.getMessage()) + "\"}", "application/json");
                        }
                    } else if ("updateStatus".equalsIgnoreCase(action)) {
                        orderDAO.updateStatus(Integer.parseInt(data.get("id")), data.get("status"));
                        sendResponse(exchange, 200, "{\"success\":true}", "application/json");
                    }
                }
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"success\":false,\"message\":\"" + escapeJson(e.getMessage()) + "\"}", "application/json");
            }
        }
    }

    // Handles User management for Admin
    static class UserHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    List<User> list = userDAO.getAll();
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < list.size(); i++) {
                        User u = list.get(i);
                        sb.append(String.format("{\"id\":%d,\"name\":\"%s\",\"email\":\"%s\",\"role\":\"%s\"}",
                                u.getId(), escapeJson(u.getName()), escapeJson(u.getEmail()), u.getRole()));
                        if (i < list.size() - 1) sb.append(",");
                    }
                    sb.append("]");
                    sendResponse(exchange, 200, sb.toString(), "application/json");
                } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    Map<String, String> data = parseFormData(readBody(exchange));
                    String action = data.getOrDefault("action", "add");
                    if ("add".equalsIgnoreCase(action)) {
                        userDAO.add(User.create(data.get("role"), 0, data.get("name"), data.get("email"), data.get("password")));
                        sendResponse(exchange, 200, "{\"success\":true}", "application/json");
                    } else if ("delete".equalsIgnoreCase(action)) {
                        userDAO.delete(Integer.parseInt(data.get("id")));
                        sendResponse(exchange, 200, "{\"success\":true}", "application/json");
                    }
                }
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"success\":false,\"message\":\"" + escapeJson(e.getMessage()) + "\"}", "application/json");
            }
        }
    }

    private static Map<String, String> parseQueryParams(String query) throws UnsupportedEncodingException {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8.name());
            String val = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name()) : "";
            map.put(key, val);
        }
        return map;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // Serves the redesigned Single Page Application from index.html on disk
    static class StaticPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String file;
            String contentType;
            if ("/favicon.svg".equals(path)) {
                file = "favicon.svg";
                contentType = "image/svg+xml";
            } else if ("/privacy".equals(path) || "/privacy.html".equals(path)) {
                file = "privacy.html";
                contentType = "text/html";
            } else if ("/terms".equals(path) || "/terms.html".equals(path)) {
                file = "terms.html";
                contentType = "text/html";
            } else if ("/".equals(path) || "/index.html".equals(path)) {
                file = "index.html";
                contentType = "text/html";
            } else {
                sendResponse(exchange, 404, "Not Found", "text/plain");
                return;
            }
            String page = readStaticFile(file);
            if (page == null) {
                sendResponse(exchange, 404, "Page not found", "text/plain");
                return;
            }
            sendResponse(exchange, 200, page, contentType);
        }
    }

    private static String readStaticFile(String filename) {
        String[] candidates = { filename, "out/" + filename, "src/" + filename };
        for (String path : candidates) {
            java.io.File f = new java.io.File(path);
            if (f.exists()) {
                try {
                    return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                } catch (IOException e) {
                    System.err.println("Could not read " + path + ": " + e.getMessage());
                }
            }
        }
        try (java.io.InputStream is = WebServer.class.getClassLoader().getResourceAsStream(filename)) {
            if (is != null) {
                return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.err.println("Classpath load failed: " + e.getMessage());
        }
        return null;
    }
}
