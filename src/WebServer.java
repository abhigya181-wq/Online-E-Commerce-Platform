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

    // Serves the full Single Page Application HTML/CSS/JS frontend
    static class StaticPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = getIndexHtml();
            sendResponse(exchange, 200, html, "text/html");
        }
    }

    private static String getIndexHtml() {
        return "<!DOCTYPE html>\n" +
"<html lang=\"en\">\n" +
"<head>\n" +
"    <meta charset=\"UTF-8\">\n" +
"    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
"    <title>E-Commerce Platform (Local Web Preview)</title>\n" +
"    <style>\n" +
"        :root {\n" +
"            --primary: #2563eb;\n" +
"            --primary-hover: #1d4ed8;\n" +
"            --bg: #f8fafc;\n" +
"            --card-bg: #ffffff;\n" +
"            --text: #0f172a;\n" +
"            --border: #e2e8f0;\n" +
"            --danger: #ef4444;\n" +
"            --success: #10b981;\n" +
"        }\n" +
"        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }\n" +
"        body { background: var(--bg); color: var(--text); padding-bottom: 40px; }\n" +
"        header { background: #1e293b; color: white; padding: 1rem 2rem; display: flex; justify-content: space-between; align-items: center; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n" +
"        .brand { font-size: 1.25rem; font-weight: bold; display: flex; align-items: center; gap: 8px; }\n" +
"        .user-tag { background: #334155; padding: 6px 14px; border-radius: 20px; font-size: 0.9rem; display: flex; align-items: center; gap: 10px; }\n" +
"        .logout-btn { background: #dc2626; color: white; border: none; padding: 4px 10px; border-radius: 4px; cursor: pointer; }\n" +
"        .container { max-width: 1100px; margin: 2rem auto; padding: 0 1rem; }\n" +
"        .card { background: var(--card-bg); border-radius: 8px; border: 1px solid var(--border); box-shadow: 0 1px 3px rgba(0,0,0,0.05); padding: 1.5rem; margin-bottom: 1.5rem; }\n" +
"        h2 { margin-bottom: 1rem; color: #1e293b; }\n" +
"        .tabs { display: flex; gap: 8px; border-bottom: 2px solid var(--border); margin-bottom: 1.5rem; }\n" +
"        .tab { padding: 8px 16px; cursor: pointer; border: none; background: none; font-weight: 600; color: #64748b; border-bottom: 2px solid transparent; margin-bottom: -2px; }\n" +
"        .tab.active { color: var(--primary); border-bottom-color: var(--primary); }\n" +
"        table { width: 100%; border-collapse: collapse; margin-top: 1rem; }\n" +
"        th, td { text-align: left; padding: 10px 12px; border-bottom: 1px solid var(--border); font-size: 0.95rem; }\n" +
"        th { background: #f1f5f9; font-weight: 600; color: #475569; }\n" +
"        tr:hover { background: #f8fafc; }\n" +
"        .form-row { display: flex; gap: 10px; margin-bottom: 1rem; flex-wrap: wrap; }\n" +
"        input, select { padding: 8px 12px; border: 1px solid var(--border); border-radius: 6px; font-size: 0.95rem; outline: none; }\n" +
"        input:focus, select:focus { border-color: var(--primary); }\n" +
"        button.btn { background: var(--primary); color: white; border: none; padding: 8px 16px; border-radius: 6px; cursor: pointer; font-weight: 500; transition: background 0.15s; }\n" +
"        button.btn:hover { background: var(--primary-hover); }\n" +
"        button.btn-danger { background: var(--danger); }\n" +
"        button.btn-sm { padding: 4px 8px; font-size: 0.85rem; }\n" +
"        .badge { padding: 4px 8px; border-radius: 12px; font-size: 0.8rem; font-weight: 600; text-transform: uppercase; }\n" +
"        .badge-pending { background: #fef3c7; color: #92400e; }\n" +
"        .badge-shipped { background: #dbeafe; color: #1e40af; }\n" +
"        .badge-delivered { background: #d1fae5; color: #065f46; }\n" +
"        .badge-cancelled { background: #fee2e2; color: #991b1b; }\n" +
"        .alert-box { background: #fee2e2; border-left: 4px solid var(--danger); color: #991b1b; padding: 12px; border-radius: 4px; margin-bottom: 1rem; font-weight: 600; display: none; }\n" +
"        .grid-auth { display: grid; grid-template-columns: 1fr 1fr; gap: 2rem; max-width: 800px; margin: 3rem auto; }\n" +
"        @media (max-width: 640px) { .grid-auth { grid-template-columns: 1fr; } }\n" +
"    </style>\n" +
"</head>\n" +
"<body>\n" +
"    <header>\n" +
"        <div class=\"brand\">🛒 E-Commerce Platform <span style=\"font-size:0.75rem; background:#22c55e; padding:2px 8px; border-radius:10px;\">LIVE</span></div>\n" +
"        <div id=\"userProfile\" style=\"display:none;\" class=\"user-tag\">\n" +
"            <span id=\"userNameTag\">User</span> (<b id=\"userRoleTag\">ROLE</b>)\n" +
"            <button class=\"logout-btn\" onclick=\"logout()\">Logout</button>\n" +
"        </div>\n" +
"    </header>\n" +
"\n" +
"    <div class=\"container\">\n" +
"        <!-- AUTH VIEW -->\n" +
"        <div id=\"authSection\" class=\"grid-auth\">\n" +
"            <div class=\"card\">\n" +
"                <h2>🔑 Login</h2>\n" +
"                <form onsubmit=\"login(event)\">\n" +
"                    <div style=\"display:flex; flex-direction:column; gap:12px;\">\n" +
"                        <input id=\"logEmail\" type=\"email\" placeholder=\"Email\" required value=\"admin@shop.com\" />\n" +
"                        <input id=\"logPass\" type=\"password\" placeholder=\"Password\" required value=\"admin123\" />\n" +
"                        <button type=\"submit\" class=\"btn\">Login</button>\n" +
"                    </div>\n" +
"                </form>\n" +
"                <div style=\"margin-top:1rem; font-size:0.85rem; color:#64748b;\">\n" +
"                    <p><b>Quick Demo Accounts:</b></p>\n" +
"                    <p>• Admin: <code>admin@shop.com</code> / <code>admin123</code></p>\n" +
"                    <p>• Seller: <code>seller@shop.com</code> / <code>seller123</code></p>\n" +
"                    <p>• Buyer: <code>buyer@shop.com</code> / <code>buyer123</code></p>\n" +
"                </div>\n" +
"            </div>\n" +
"            <div class=\"card\">\n" +
"                <h2>📝 Register</h2>\n" +
"                <form onsubmit=\"register(event)\">\n" +
"                    <div style=\"display:flex; flex-direction:column; gap:12px;\">\n" +
"                        <input id=\"regName\" type=\"text\" placeholder=\"Full Name\" required />\n" +
"                        <input id=\"regEmail\" type=\"email\" placeholder=\"Email Address\" required />\n" +
"                        <input id=\"regPass\" type=\"password\" placeholder=\"Password\" required />\n" +
"                        <select id=\"regRole\">\n" +
"                            <option value=\"BUYER\">BUYER</option>\n" +
"                            <option value=\"SELLER\">SELLER</option>\n" +
"                        </select>\n" +
"                        <button type=\"submit\" class=\"btn\">Create Account</button>\n" +
"                    </div>\n" +
"                </form>\n" +
"            </div>\n" +
"        </div>\n" +
"\n" +
"        <!-- DASHBOARDS -->\n" +
"        <div id=\"dashboardSection\" style=\"display:none;\">\n" +
"            <!-- BUYER VIEW -->\n" +
"            <div id=\"buyerView\" style=\"display:none;\">\n" +
"                <div class=\"tabs\">\n" +
"                    <button class=\"tab active\" onclick=\"switchTab('buyer-catalog', this)\">Browse Products</button>\n" +
"                    <button class=\"tab\" onclick=\"switchTab('buyer-orders', this)\">My Orders</button>\n" +
"                </div>\n" +
"                <div id=\"buyer-catalog\" class=\"tab-content\">\n" +
"                    <div class=\"card\">\n" +
"                        <div class=\"form-row\">\n" +
"                            <input id=\"buyerSearchInput\" type=\"text\" placeholder=\"Search products by name...\" style=\"flex:1;\" />\n" +
"                            <button class=\"btn\" onclick=\"loadBuyerCatalog()\">Search</button>\n" +
"                            <button class=\"btn\" style=\"background:#64748b;\" onclick=\"document.getElementById('buyerSearchInput').value=''; loadBuyerCatalog();\">Show All</button>\n" +
"                        </div>\n" +
"                        <table id=\"buyerCatalogTable\">\n" +
"                            <thead><tr><th>ID</th><th>Name</th><th>Price</th><th>Stock</th><th>Action</th></tr></thead>\n" +
"                            <tbody></tbody>\n" +
"                        </table>\n" +
"                    </div>\n" +
"                </div>\n" +
"                <div id=\"buyer-orders\" class=\"tab-content\" style=\"display:none;\">\n" +
"                    <div class=\"card\">\n" +
"                        <h2>My Placed Orders</h2>\n" +
"                        <table id=\"buyerOrdersTable\">\n" +
"                            <thead><tr><th>Order ID</th><th>Product</th><th>Quantity</th><th>Total</th><th>Status</th></tr></thead>\n" +
"                            <tbody></tbody>\n" +
"                        </table>\n" +
"                    </div>\n" +
"                </div>\n" +
"            </div>\n" +
"\n" +
"            <!-- SELLER VIEW -->\n" +
"            <div id=\"sellerView\" style=\"display:none;\">\n" +
"                <div id=\"sellerLowStockAlert\" class=\"alert-box\"></div>\n" +
"                <div class=\"tabs\">\n" +
"                    <button class=\"tab active\" onclick=\"switchTab('seller-products', this)\">My Products</button>\n" +
"                    <button class=\"tab\" onclick=\"switchTab('seller-orders', this)\">Orders Received</button>\n" +
"                </div>\n" +
"                <div id=\"seller-products\" class=\"tab-content\">\n" +
"                    <div class=\"card\">\n" +
"                        <h3>Add New Product</h3>\n" +
"                        <div class=\"form-row\" style=\"margin-top:10px;\">\n" +
"                            <input id=\"newProdName\" placeholder=\"Product Name\" style=\"flex:2;\" />\n" +
"                            <input id=\"newProdPrice\" type=\"number\" step=\"0.01\" placeholder=\"Price ($)\" style=\"flex:1;\" />\n" +
"                            <input id=\"newProdStock\" type=\"number\" placeholder=\"Stock Qty\" style=\"flex:1;\" />\n" +
"                            <button class=\"btn\" onclick=\"sellerAddProduct()\">Add Product</button>\n" +
"                        </div>\n" +
"                        <h3 style=\"margin-top:20px;\">Inventory Catalog</h3>\n" +
"                        <table id=\"sellerProductsTable\">\n" +
"                            <thead><tr><th>ID</th><th>Name</th><th>Price</th><th>Stock</th><th>Actions</th></tr></thead>\n" +
"                            <tbody></tbody>\n" +
"                        </table>\n" +
"                    </div>\n" +
"                </div>\n" +
"                <div id=\"seller-orders\" class=\"tab-content\" style=\"display:none;\">\n" +
"                    <div class=\"card\">\n" +
"                        <h2>Customer Orders</h2>\n" +
"                        <table id=\"sellerOrdersTable\">\n" +
"                            <thead><tr><th>Order ID</th><th>Buyer</th><th>Product</th><th>Qty</th><th>Total</th><th>Status</th><th>Update</th></tr></thead>\n" +
"                            <tbody></tbody>\n" +
"                        </table>\n" +
"                    </div>\n" +
"                </div>\n" +
"            </div>\n" +
"\n" +
"            <!-- ADMIN VIEW -->\n" +
"            <div id=\"adminView\" style=\"display:none;\">\n" +
"                <div class=\"tabs\">\n" +
"                    <button class=\"tab active\" onclick=\"switchTab('admin-users', this)\">Users</button>\n" +
"                    <button class=\"tab\" onclick=\"switchTab('admin-products', this)\">Products</button>\n" +
"                    <button class=\"tab\" onclick=\"switchTab('admin-orders', this)\">All Orders (Live)</button>\n" +
"                </div>\n" +
"                <div id=\"admin-users\" class=\"tab-content\">\n" +
"                    <div class=\"card\">\n" +
"                        <h3>Create User</h3>\n" +
"                        <div class=\"form-row\" style=\"margin-top:10px;\">\n" +
"                            <input id=\"adminUName\" placeholder=\"Name\" style=\"flex:1;\" />\n" +
"                            <input id=\"adminUEmail\" placeholder=\"Email\" style=\"flex:1;\" />\n" +
"                            <input id=\"adminUPass\" type=\"password\" placeholder=\"Password\" style=\"flex:1;\" />\n" +
"                            <select id=\"adminURole\"><option value=\"ADMIN\">ADMIN</option><option value=\"SELLER\">SELLER</option><option value=\"BUYER\">BUYER</option></select>\n" +
"                            <button class=\"btn\" onclick=\"adminAddUser()\">Create</button>\n" +
"                        </div>\n" +
"                        <table id=\"adminUsersTable\">\n" +
"                            <thead><tr><th>ID</th><th>Name</th><th>Email</th><th>Role</th><th>Action</th></tr></thead>\n" +
"                            <tbody></tbody>\n" +
"                        </table>\n" +
"                    </div>\n" +
"                </div>\n" +
"                <div id=\"admin-products\" class=\"tab-content\" style=\"display:none;\">\n" +
"                    <div class=\"card\">\n" +
"                        <h3>System Catalog Moderation</h3>\n" +
"                        <table id=\"adminProductsTable\">\n" +
"                            <thead><tr><th>ID</th><th>Seller ID</th><th>Name</th><th>Price</th><th>Stock</th><th>Action</th></tr></thead>\n" +
"                            <tbody></tbody>\n" +
"                        </table>\n" +
"                    </div>\n" +
"                </div>\n" +
"                <div id=\"admin-orders\" class=\"tab-content\" style=\"display:none;\">\n" +
"                    <div class=\"card\">\n" +
"                        <div style=\"display:flex; justify-content:space-between; align-items:center;\">\n" +
"                            <h3>Live Order Activity (Auto-refreshes every 3s)</h3>\n" +
"                            <span style=\"font-size:0.85rem; color:#10b981;\">● Auto-sync Active</span>\n" +
"                        </div>\n" +
"                        <table id=\"adminOrdersTable\">\n" +
"                            <thead><tr><th>Order ID</th><th>Buyer</th><th>Product</th><th>Qty</th><th>Total</th><th>Status</th><th>Action</th></tr></thead>\n" +
"                            <tbody></tbody>\n" +
"                        </table>\n" +
"                    </div>\n" +
"                </div>\n" +
"            </div>\n" +
"        </div>\n" +
"    </div>\n" +
"\n" +
"    <script>\n" +
"        let currentUser = null;\n" +
"        let autoRefreshInterval = null;\n" +
"\n" +
"        async function login(e) {\n" +
"            e.preventDefault();\n" +
"            const email = document.getElementById('logEmail').value;\n" +
"            const password = document.getElementById('logPass').value;\n" +
"            const res = await fetch('/api/login', {\n" +
"                method: 'POST',\n" +
"                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
"                body: new URLSearchParams({ email, password })\n" +
"            });\n" +
"            const data = await res.json();\n" +
"            if (data.success) {\n" +
"                currentUser = data.user;\n" +
"                renderSession();\n" +
"            } else {\n" +
"                alert(data.message || 'Login failed');\n" +
"            }\n" +
"        }\n" +
"\n" +
"        async function register(e) {\n" +
"            e.preventDefault();\n" +
"            const name = document.getElementById('regName').value;\n" +
"            const email = document.getElementById('regEmail').value;\n" +
"            const password = document.getElementById('regPass').value;\n" +
"            const role = document.getElementById('regRole').value;\n" +
"            const res = await fetch('/api/register', {\n" +
"                method: 'POST',\n" +
"                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
"                body: new URLSearchParams({ name, email, password, role })\n" +
"            });\n" +
"            const data = await res.json();\n" +
"            if (data.success) {\n" +
"                alert('Registration successful! Please login.');\n" +
"                document.getElementById('logEmail').value = email;\n" +
"                document.getElementById('logPass').value = password;\n" +
"            } else {\n" +
"                alert(data.message || 'Registration failed');\n" +
"            }\n" +
"        }\n" +
"\n" +
"        function logout() {\n" +
"            currentUser = null;\n" +
"            if (autoRefreshInterval) clearInterval(autoRefreshInterval);\n" +
"            document.getElementById('userProfile').style.display = 'none';\n" +
"            document.getElementById('authSection').style.display = 'grid';\n" +
"            document.getElementById('dashboardSection').style.display = 'none';\n" +
"        }\n" +
"\n" +
"        function renderSession() {\n" +
"            document.getElementById('authSection').style.display = 'none';\n" +
"            document.getElementById('dashboardSection').style.display = 'block';\n" +
"            document.getElementById('userProfile').style.display = 'flex';\n" +
"            document.getElementById('userNameTag').innerText = currentUser.name;\n" +
"            document.getElementById('userRoleTag').innerText = currentUser.role;\n" +
"\n" +
"            document.getElementById('buyerView').style.display = currentUser.role === 'BUYER' ? 'block' : 'none';\n" +
"            document.getElementById('sellerView').style.display = currentUser.role === 'SELLER' ? 'block' : 'none';\n" +
"            document.getElementById('adminView').style.display = currentUser.role === 'ADMIN' ? 'block' : 'none';\n" +
"\n" +
"            if (currentUser.role === 'BUYER') {\n" +
"                loadBuyerCatalog();\n" +
"                loadBuyerOrders();\n" +
"            } else if (currentUser.role === 'SELLER') {\n" +
"                loadSellerProducts();\n" +
"                loadSellerOrders();\n" +
"            } else if (currentUser.role === 'ADMIN') {\n" +
"                loadAdminUsers();\n" +
"                loadAdminProducts();\n" +
"                loadAdminOrders();\n" +
"                if (autoRefreshInterval) clearInterval(autoRefreshInterval);\n" +
"                autoRefreshInterval = setInterval(loadAdminOrders, 3000);\n" +
"            }\n" +
"        }\n" +
"\n" +
"        function switchTab(targetId, tabBtn) {\n" +
"            const parent = tabBtn.parentElement;\n" +
"            parent.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));\n" +
"            tabBtn.classList.add('active');\n" +
"            const container = parent.parentElement;\n" +
"            container.querySelectorAll('.tab-content').forEach(c => c.style.display = 'none');\n" +
"            document.getElementById(targetId).style.display = 'block';\n" +
"        }\n" +
"\n" +
"        // Buyer methods\n" +
"        async function loadBuyerCatalog() {\n" +
"            const q = document.getElementById('buyerSearchInput').value.trim();\n" +
"            const res = await fetch('/api/products' + (q ? '?search=' + encodeURIComponent(q) : ''));\n" +
"            const products = await res.json();\n" +
"            const tbody = document.querySelector('#buyerCatalogTable tbody');\n" +
"            tbody.innerHTML = products.map(p => `\n" +
"                <tr>\n" +
"                    <td>${p.id}</td>\n" +
"                    <td><b>${p.name}</b></td>\n" +
"                    <td>$${p.price.toFixed(2)}</td>\n" +
"                    <td><span style=\"font-weight:600; color:${p.stock < 5 ? '#ef4444' : '#10b981'}\">${p.stock}</span></td>\n" +
"                    <td><button class=\"btn btn-sm\" onclick=\"buyProduct(${p.id})\">Buy</button></td>\n" +
"                </tr>\n" +
"            `).join('');\n" +
"        }\n" +
"\n" +
"        async function buyProduct(productId) {\n" +
"            const qtyStr = prompt('Enter quantity to purchase:', '1');\n" +
"            if (!qtyStr) return;\n" +
"            const res = await fetch('/api/orders', {\n" +
"                method: 'POST',\n" +
"                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
"                body: new URLSearchParams({\n" +
"                    action: 'place',\n" +
"                    buyer_id: currentUser.id,\n" +
"                    product_id: productId,\n" +
"                    quantity: qtyStr\n" +
"                })\n" +
"            });\n" +
"            const data = await res.json();\n" +
"            if (data.success) {\n" +
"                alert('Order placed successfully!');\n" +
"                loadBuyerCatalog();\n" +
"                loadBuyerOrders();\n" +
"            } else {\n" +
"                alert('Error: ' + data.message);\n" +
"            }\n" +
"        }\n" +
"\n" +
"        async function loadBuyerOrders() {\n" +
"            const res = await fetch('/api/orders?buyer_id=' + currentUser.id);\n" +
"            const orders = await res.json();\n" +
"            const tbody = document.querySelector('#buyerOrdersTable tbody');\n" +
"            tbody.innerHTML = orders.map(o => `\n" +
"                <tr>\n" +
"                    <td>#${o.id}</td>\n" +
"                    <td>${o.product}</td>\n" +
"                    <td>${o.qty}</td>\n" +
"                    <td>$${o.total.toFixed(2)}</td>\n" +
"                    <td><span class=\"badge badge-${o.status.toLowerCase()}\">${o.status}</span></td>\n" +
"                </tr>\n" +
"            `).join('');\n" +
"        }\n" +
"\n" +
"        // Seller methods\n" +
"        async function loadSellerProducts() {\n" +
"            const res = await fetch('/api/products?seller_id=' + currentUser.id);\n" +
"            const products = await res.json();\n" +
"            const tbody = document.querySelector('#sellerProductsTable tbody');\n" +
"            const lowStockAlert = document.getElementById('sellerLowStockAlert');\n" +
"            const lowStockItems = products.filter(p => p.stock < 5);\n" +
"            if (lowStockItems.length > 0) {\n" +
"                lowStockAlert.style.display = 'block';\n" +
"                lowStockAlert.innerText = '⚠️ LOW STOCK ALERT: ' + lowStockItems.map(p => `${p.name} (${p.stock} left)`).join(', ');\n" +
"            } else {\n" +
"                lowStockAlert.style.display = 'none';\n" +
"            }\n" +
"            tbody.innerHTML = products.map(p => `\n" +
"                <tr>\n" +
"                    <td>${p.id}</td>\n" +
"                    <td>${p.name}</td>\n" +
"                    <td>$${p.price.toFixed(2)}</td>\n" +
"                    <td>${p.stock}</td>\n" +
"                    <td>\n" +
"                        <button class=\"btn btn-sm\" onclick=\"updateStock(${p.id})\">Update Stock</button>\n" +
"                        <button class=\"btn btn-sm btn-danger\" onclick=\"deleteProduct(${p.id})\">Delete</button>\n" +
"                    </td>\n" +
"                </tr>\n" +
"            `).join('');\n" +
"        }\n" +
"\n" +
"        async function sellerAddProduct() {\n" +
"            const name = document.getElementById('newProdName').value;\n" +
"            const price = document.getElementById('newProdPrice').value;\n" +
"            const stock = document.getElementById('newProdStock').value;\n" +
"            if (!name || !price || !stock) return alert('Fill all product fields');\n" +
"            await fetch('/api/products', {\n" +
"                method: 'POST',\n" +
"                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
"                body: new URLSearchParams({ action: 'add', seller_id: currentUser.id, name, price, stock })\n" +
"            });\n" +
"            document.getElementById('newProdName').value = '';\n" +
"            document.getElementById('newProdPrice').value = '';\n" +
"            document.getElementById('newProdStock').value = '';\n" +
"            loadSellerProducts();\n" +
"        }\n" +
"\n" +
"        async function updateStock(id) {\n" +
"            const newStock = prompt('Enter new stock quantity:');\n" +
"            if (!newStock) return;\n" +
"            await fetch('/api/products', {\n" +
"                method: 'POST',\n" +
"                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
"                body: new URLSearchParams({ action: 'updateStock', id, stock: newStock })\n" +
"            });\n" +
"            loadSellerProducts();\n" +
"        }\n" +
"\n" +
"        async function deleteProduct(id) {\n" +
"            if (!confirm('Are you sure you want to delete this product?')) return;\n" +
"            await fetch('/api/products', {\n" +
"                method: 'POST',\n" +
"                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
"                body: new URLSearchParams({ action: 'delete', id })\n" +
"            });\n" +
"            loadSellerProducts();\n" +
"        }\n" +
"\n" +
"        async function loadSellerOrders() {\n" +
"            const res = await fetch('/api/orders?seller_id=' + currentUser.id);\n" +
"            const orders = await res.json();\n" +
"            const tbody = document.querySelector('#sellerOrdersTable tbody');\n" +
"            tbody.innerHTML = orders.map(o => `\n" +
"                <tr>\n" +
"                    <td>#${o.id}</td>\n" +
"                    <td>${o.buyer}</td>\n" +
"                    <td>${o.product}</td>\n" +
"                    <td>${o.qty}</td>\n" +
"                    <td>$${o.total.toFixed(2)}</td>\n" +
"                    <td><span class=\"badge badge-${o.status.toLowerCase()}\">${o.status}</span></td>\n" +
"                    <td>\n" +
"                        <select id=\"sel-status-${o.id}\">\n" +
"                            <option ${o.status==='Pending'?'selected':''}>Pending</option>\n" +
"                            <option ${o.status==='Shipped'?'selected':''}>Shipped</option>\n" +
"                            <option ${o.status==='Delivered'?'selected':''}>Delivered</option>\n" +
"                            <option ${o.status==='Cancelled'?'selected':''}>Cancelled</option>\n" +
"                        </select>\n" +
"                        <button class=\"btn btn-sm\" onclick=\"updateOrderStatus(${o.id}, 'sel-status-${o.id}')\">Save</button>\n" +
"                    </td>\n" +
"                </tr>\n" +
"            `).join('');\n" +
"        }\n" +
"\n" +
"        async function updateOrderStatus(orderId, selectId) {\n" +
"            const status = document.getElementById(selectId).value;\n" +
"            await fetch('/api/orders', {\n" +
"                method: 'POST',\n" +
"                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
"                body: new URLSearchParams({ action: 'updateStatus', id: orderId, status })\n" +
"            });\n" +
"            alert('Order #' + orderId + ' updated to ' + status);\n" +
"            if (currentUser.role === 'SELLER') loadSellerOrders();\n" +
"            if (currentUser.role === 'ADMIN') loadAdminOrders();\n" +
"        }\n" +
"\n" +
"        // Admin methods\n" +
"        async function loadAdminUsers() {\n" +
"            const res = await fetch('/api/users');\n" +
"            const users = await res.json();\n" +
"            const tbody = document.querySelector('#adminUsersTable tbody');\n" +
"            tbody.innerHTML = users.map(u => `\n" +
"                <tr>\n" +
"                    <td>${u.id}</td>\n" +
"                    <td>${u.name}</td>\n" +
"                    <td>${u.email}</td>\n" +
"                    <td><b>${u.role}</b></td>\n" +
"                    <td><button class=\"btn btn-sm btn-danger\" onclick=\"adminDeleteUser(${u.id})\">Delete</button></td>\n" +
"                </tr>\n" +
"            `).join('');\n" +
"        }\n" +
"\n" +
"        async function adminAddUser() {\n" +
"            const name = document.getElementById('adminUName').value;\n" +
"            const email = document.getElementById('adminUEmail').value;\n" +
"            const password = document.getElementById('adminUPass').value;\n" +
"            const role = document.getElementById('adminURole').value;\n" +
"            if (!name || !email || !password) return alert('Fill all user fields');\n" +
"            await fetch('/api/users', {\n" +
"                method: 'POST',\n" +
"                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
"                body: new URLSearchParams({ action: 'add', name, email, password, role })\n" +
"            });\n" +
"            document.getElementById('adminUName').value = '';\n" +
"            document.getElementById('adminUEmail').value = '';\n" +
"            document.getElementById('adminUPass').value = '';\n" +
"            loadAdminUsers();\n" +
"        }\n" +
"\n" +
"        async function adminDeleteUser(id) {\n" +
"            if (!confirm('Are you sure you want to delete user ID ' + id + '?')) return;\n" +
"            await fetch('/api/users', {\n" +
"                method: 'POST',\n" +
"                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
"                body: new URLSearchParams({ action: 'delete', id })\n" +
"            });\n" +
"            loadAdminUsers();\n" +
"        }\n" +
"\n" +
"        async function loadAdminProducts() {\n" +
"            const res = await fetch('/api/products');\n" +
"            const products = await res.json();\n" +
"            const tbody = document.querySelector('#adminProductsTable tbody');\n" +
"            tbody.innerHTML = products.map(p => `\n" +
"                <tr>\n" +
"                    <td>${p.id}</td>\n" +
"                    <td>${p.sellerId}</td>\n" +
"                    <td>${p.name}</td>\n" +
"                    <td>$${p.price.toFixed(2)}</td>\n" +
"                    <td>${p.stock}</td>\n" +
"                    <td><button class=\"btn btn-sm btn-danger\" onclick=\"adminDeleteProduct(${p.id})\">Remove</button></td>\n" +
"                </tr>\n" +
"            `).join('');\n" +
"        }\n" +
"\n" +
"        async function adminDeleteProduct(id) {\n" +
"            if (!confirm('Remove this product from the platform?')) return;\n" +
"            await fetch('/api/products', {\n" +
"                method: 'POST',\n" +
"                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
"                body: new URLSearchParams({ action: 'delete', id })\n" +
"            });\n" +
"            loadAdminProducts();\n" +
"        }\n" +
"\n" +
"        async function loadAdminOrders() {\n" +
"            const res = await fetch('/api/orders');\n" +
"            const orders = await res.json();\n" +
"            const tbody = document.querySelector('#adminOrdersTable tbody');\n" +
"            tbody.innerHTML = orders.map(o => `\n" +
"                <tr>\n" +
"                    <td>#${o.id}</td>\n" +
"                    <td>${o.buyer}</td>\n" +
"                    <td>${o.product}</td>\n" +
"                    <td>${o.qty}</td>\n" +
"                    <td>$${o.total.toFixed(2)}</td>\n" +
"                    <td><span class=\"badge badge-${o.status.toLowerCase()}\">${o.status}</span></td>\n" +
"                    <td>\n" +
"                        <select id=\"adm-status-${o.id}\">\n" +
"                            <option ${o.status==='Pending'?'selected':''}>Pending</option>\n" +
"                            <option ${o.status==='Shipped'?'selected':''}>Shipped</option>\n" +
"                            <option ${o.status==='Delivered'?'selected':''}>Delivered</option>\n" +
"                            <option ${o.status==='Cancelled'?'selected':''}>Cancelled</option>\n" +
"                        </select>\n" +
"                        <button class=\"btn btn-sm\" onclick=\"updateOrderStatus(${o.id}, 'adm-status-${o.id}')\">Update</button>\n" +
"                    </td>\n" +
"                </tr>\n" +
"            `).join('');\n" +
"        }\n" +
"    </script>\n" +
"</body>\n" +
"</html>";
    }
}
