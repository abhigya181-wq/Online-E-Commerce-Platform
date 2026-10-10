import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WebServer {
    private static final UserDAO userDAO = new UserDAO();
    private static final ProductDAO productDAO = new ProductDAO();
    private static final OrderDAO orderDAO = new OrderDAO();
    private static final Map<String, User> sessions = new ConcurrentHashMap<>();
    private static final SecureRandom sessionRandom = new SecureRandom();

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
        server.createContext("/api/session", new SessionHandler());
        server.createContext("/api/logout", new LogoutHandler());
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

    private static String cookieValue(HttpExchange exchange, String name) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) return null;
        for (String header : cookies) {
            for (String part : header.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && name.equals(pair[0])) return pair[1];
            }
        }
        return null;
    }

    private static User sessionUser(HttpExchange exchange) {
        String token = cookieValue(exchange, "SHOPWELL_SESSION");
        return token == null ? null : sessions.get(token);
    }

    private static void setSessionCookie(HttpExchange exchange, String token, boolean clear) {
        boolean secure = "https".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"));
        String value = "SHOPWELL_SESSION=" + (clear ? "" : token) + "; Path=/; HttpOnly; SameSite=Strict";
        if (secure) value += "; Secure";
        if (clear) value += "; Max-Age=0";
        exchange.getResponseHeaders().add("Set-Cookie", value);
    }

    private static String newSessionToken() {
        byte[] bytes = new byte[32];
        sessionRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void sendError(HttpExchange exchange, int code, String message) throws IOException {
        sendResponse(exchange, code, "{\"success\":false,\"message\":\"" + escapeJson(message) + "\"}", "application/json");
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
                        String token = newSessionToken();
                        sessions.put(token, u);
                        setSessionCookie(exchange, token, false);
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

    static class LogoutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String token = cookieValue(exchange, "SHOPWELL_SESSION");
            if (token != null) sessions.remove(token);
            setSessionCookie(exchange, "", true);
            sendResponse(exchange, 200, "{\"success\":true}", "application/json");
        }
    }

    static class SessionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            User user = sessionUser(exchange);
            if (user == null) { sendError(exchange, 401, "No active session."); return; }
            String json = String.format(Locale.US,
                    "{\"success\":true,\"user\":{\"id\":%d,\"name\":\"%s\",\"email\":\"%s\",\"role\":\"%s\"}}",
                    user.getId(), escapeJson(user.getName()), escapeJson(user.getEmail()), user.getRole());
            sendResponse(exchange, 200, json, "application/json");
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
                    if (name == null || name.trim().isEmpty() || email == null || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
                            || pass == null || pass.length() < 8 || !("BUYER".equals(role) || "SELLER".equals(role))) {
                        sendError(exchange, 400, "Enter a name, valid email, password with at least 8 characters, and Buyer or Seller role.");
                        return;
                    }
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
                        User actor = sessionUser(exchange);
                        if (actor == null) { sendError(exchange, 401, "Sign in to view seller inventory."); return; }
                        int requestedSeller = Integer.parseInt(params.get("seller_id"));
                        if ("SELLER".equals(actor.getRole()) && actor.getId() != requestedSeller) { sendError(exchange, 403, "You can only view your own inventory."); return; }
                        if (!("SELLER".equals(actor.getRole()) || "ADMIN".equals(actor.getRole()))) { sendError(exchange, 403, "This account cannot view seller inventory."); return; }
                        list = productDAO.getBySeller("ADMIN".equals(actor.getRole()) ? requestedSeller : actor.getId());
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
                    User actor = sessionUser(exchange);
                    if (actor == null) { sendError(exchange, 401, "Sign in to manage products."); return; }
                    if (!("SELLER".equals(actor.getRole()) || "ADMIN".equals(actor.getRole()))) { sendError(exchange, 403, "Only sellers can manage product listings."); return; }
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
                        int sellerId = "ADMIN".equals(actor.getRole()) ? Integer.parseInt(data.get("seller_id")) : actor.getId();
                        productDAO.add(new Product(0, sellerId,
                                name.trim(), price, stock));
                        sendResponse(exchange, 200, "{\"success\":true}", "application/json");
                    } else if ("updateStock".equalsIgnoreCase(action)) {
                        int stock = Integer.parseInt(data.get("stock"));
                        if (stock < 0) {
                            sendResponse(exchange, 400, "{\"success\":false,\"message\":\"Stock cannot be negative.\"}", "application/json");
                            return;
                        }
                        if ("ADMIN".equals(actor.getRole())) productDAO.updateStock(Integer.parseInt(data.get("id")), stock);
                        else productDAO.updateStockForSeller(Integer.parseInt(data.get("id")), stock, actor.getId());
                        sendResponse(exchange, 200, "{\"success\":true}", "application/json");
                    } else if ("delete".equalsIgnoreCase(action)) {
                        if ("ADMIN".equals(actor.getRole())) productDAO.delete(Integer.parseInt(data.get("id")));
                        else productDAO.deleteForSeller(Integer.parseInt(data.get("id")), actor.getId());
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
                    User actor = sessionUser(exchange);
                    if (actor == null) { sendError(exchange, 401, "Sign in to view orders."); return; }
                    Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
                    List<Object[]> rows;
                    if ("BUYER".equals(actor.getRole())) {
                        rows = orderDAO.getByBuyer(actor.getId());
                        if (params.containsKey("checkout_id")) {
                            String requestedCheckout = params.get("checkout_id");
                            rows.removeIf(row -> !requestedCheckout.equals(row[6]));
                        }
                    }
                    else if ("SELLER".equals(actor.getRole())) rows = orderDAO.getBySeller(actor.getId());
                    else if ("ADMIN".equals(actor.getRole())) {
                        rows = orderDAO.getAll();
                    } else { sendError(exchange, 403, "This account cannot view orders."); return; }

                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < rows.size(); i++) {
                        Object[] r = rows.get(i);
                        sb.append(String.format(Locale.US,
                                "{\"id\":%d,\"buyer\":\"%s\",\"product\":\"%s\",\"qty\":%d,\"total\":%.2f,\"status\":\"%s\",\"checkoutId\":\"%s\",\"createdAt\":\"%s\",\"shippingName\":\"%s\",\"shippingAddress\":\"%s\",\"shippingCity\":\"%s\",\"shippingPostal\":\"%s\",\"paymentMethod\":\"%s\",\"tax\":%.2f,\"shipping\":%.2f,\"checkoutTotal\":%.2f,\"productId\":%d}",
                                (int) r[0], escapeJson((String) r[1]), escapeJson((String) r[2]), (int) r[3], (double) r[4],
                                escapeJson((String) r[5]), escapeJson((String) r[6]), escapeJson((String) r[7]),
                                escapeJson((String) r[8]), escapeJson((String) r[9]), escapeJson((String) r[10]),
                                escapeJson((String) r[11]), escapeJson((String) r[12]), (double) r[13], (double) r[14], (double) r[15], (int)r[16]));
                        if (i < rows.size() - 1) sb.append(",");
                    }
                    sb.append("]");
                    sendResponse(exchange, 200, sb.toString(), "application/json");
                } else if ("POST".equalsIgnoreCase(method)) {
                    User actor = sessionUser(exchange);
                    if (actor == null) { sendError(exchange, 401, "Sign in to continue."); return; }
                    Map<String, String> data = parseFormData(readBody(exchange));
                    String action = data.getOrDefault("action", "place");
                    if ("checkout".equalsIgnoreCase(action)) {
                        if (!"BUYER".equals(actor.getRole())) { sendError(exchange, 403, "Only buyer accounts can check out."); return; }
                        try {
                            String name = required(data, "name", "Delivery name");
                            String address = required(data, "address", "Street address");
                            String city = required(data, "city", "City");
                            String postal = required(data, "postal", "Postal code");
                            String payment = data.getOrDefault("payment", "");
                            if (!("Demo card".equals(payment) || "Cash on delivery".equals(payment))) {
                                sendError(exchange, 400, "Choose one of the demo payment methods."); return;
                            }
                            List<int[]> items = new ArrayList<>();
                            for (String line : data.getOrDefault("items", "").split(",")) {
                                if (line.trim().isEmpty()) continue;
                                String[] pair = line.split(":", 2);
                                if (pair.length != 2) throw new IllegalArgumentException("Cart data is invalid.");
                                items.add(new int[]{Integer.parseInt(pair[0]), Integer.parseInt(pair[1])});
                            }
                            String checkoutId = orderDAO.placeCheckout(actor.getId(), items, name, address, city, postal, payment);
                            sendResponse(exchange, 200, "{\"success\":true,\"checkoutId\":\"" + checkoutId + "\"}", "application/json");
                        } catch (StockException se) {
                            sendError(exchange, 400, se.getMessage());
                        } catch (IllegalArgumentException ex) {
                            sendError(exchange, 400, ex.getMessage());
                        }
                    } else if ("cancel".equalsIgnoreCase(action)) {
                        if (!"BUYER".equals(actor.getRole())) { sendError(exchange, 403, "Only buyers can cancel their orders."); return; }
                        orderDAO.cancelCheckout(actor.getId(), required(data, "checkoutId", "Order"));
                        sendResponse(exchange, 200, "{\"success\":true}", "application/json");
                    } else if ("updateStatus".equalsIgnoreCase(action)) {
                        String status = data.get("status");
                        if (!("Pending".equals(status) || "Processing".equals(status) || "Shipped".equals(status) || "Delivered".equals(status) || "Cancelled".equals(status))) {
                            sendError(exchange, 400, "Choose a valid order status."); return;
                        }
                        if ("ADMIN".equals(actor.getRole())) orderDAO.updateStatus(Integer.parseInt(data.get("id")), status);
                        else if ("SELLER".equals(actor.getRole())) orderDAO.updateStatusForSeller(Integer.parseInt(data.get("id")), actor.getId(), status);
                        else { sendError(exchange, 403, "This account cannot update order status."); return; }
                        sendResponse(exchange, 200, "{\"success\":true}", "application/json");
                    }
                }
            } catch (Exception e) {
                int code = (e instanceof IllegalArgumentException || e instanceof StockException) ? 400 : 500;
                sendError(exchange, code, e.getMessage() == null ? "Order request failed." : e.getMessage());
            }
        }
    }

    private static String required(Map<String, String> data, String key, String label) {
        String value = data.get(key);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + " is required.");
        return value.trim();
    }

    // Handles User management for Admin
    static class UserHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                User actor = sessionUser(exchange);
                if (actor == null) { sendError(exchange, 401, "Sign in as an administrator to manage users."); return; }
                if (!"ADMIN".equals(actor.getRole())) { sendError(exchange, 403, "Only administrators can manage users."); return; }
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
                        String name = data.get("name"), email = data.get("email"), password = data.get("password"), role = data.get("role");
                        if (name == null || name.trim().isEmpty() || email == null || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
                                || password == null || password.length() < 8 || !("BUYER".equals(role) || "SELLER".equals(role) || "ADMIN".equals(role))) {
                            sendError(exchange, 400, "Enter a name, valid email, password with at least 8 characters, and a valid role.");
                            return;
                        }
                        userDAO.add(User.create(role, 0, name.trim(), email.trim(), password));
                        sendResponse(exchange, 200, "{\"success\":true}", "application/json");
                    } else if ("delete".equalsIgnoreCase(action)) {
                        int userId = Integer.parseInt(data.get("id"));
                        if (userId == actor.getId()) { sendError(exchange, 400, "Use a different administrator account before deleting this one."); return; }
                        userDAO.delete(userId);
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
