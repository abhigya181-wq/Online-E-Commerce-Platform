import java.sql.*;
import java.util.Locale;
import java.util.UUID;

public class DBConnection {

    // Returns a connection to the SQLite database file (supports DB_PATH environment variable for cloud deployment)
    public static Connection getConnection() throws SQLException {
        String dbPath = System.getenv("DB_PATH");
        if (dbPath == null || dbPath.trim().isEmpty()) {
            dbPath = "shop.db";
        } else {
            java.io.File file = new java.io.File(dbPath);
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
        }
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }


    // Creates the required tables and seeds default accounts
    public static void createTables() {
        try (Connection con = getConnection(); Statement st = con.createStatement()) {
            // 1. Users table (Admin, Seller, Buyer accounts)
            st.execute("CREATE TABLE IF NOT EXISTS users("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "name TEXT, "
                    + "email TEXT UNIQUE, "
                    + "password TEXT, "
                    + "role TEXT)");

            // 2. Products table
            st.execute("CREATE TABLE IF NOT EXISTS products("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "seller_id INTEGER, "
                    + "name TEXT, "
                    + "price REAL, "
                    + "stock INTEGER)");

            // 3. Orders table
            st.execute("CREATE TABLE IF NOT EXISTS orders("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "buyer_id INTEGER, "
                    + "product_id INTEGER, "
                    + "quantity INTEGER, "
                    + "total REAL, "
                    + "status TEXT)");

            // Checkout details are nullable so existing desktop orders remain readable.
            ensureOrderColumn(con, st, "checkout_id", "TEXT");
            ensureOrderColumn(con, st, "created_at", "TEXT NOT NULL DEFAULT ''");
            ensureOrderColumn(con, st, "shipping_name", "TEXT NOT NULL DEFAULT ''");
            ensureOrderColumn(con, st, "shipping_address", "TEXT NOT NULL DEFAULT ''");
            ensureOrderColumn(con, st, "shipping_city", "TEXT NOT NULL DEFAULT ''");
            ensureOrderColumn(con, st, "shipping_postal", "TEXT NOT NULL DEFAULT ''");
            ensureOrderColumn(con, st, "payment_method", "TEXT NOT NULL DEFAULT 'Demo checkout'");
            ensureOrderColumn(con, st, "tax", "REAL NOT NULL DEFAULT 0");
            ensureOrderColumn(con, st, "shipping", "REAL NOT NULL DEFAULT 0");
            ensureOrderColumn(con, st, "checkout_total", "REAL NOT NULL DEFAULT 0");

            // Keep public demo credentials out of hosted deployments. A production admin
            // can be bootstrapped with ADMIN_EMAIL, ADMIN_PASSWORD, and optional ADMIN_NAME.
            boolean localDemo = System.getenv("PORT") == null || System.getenv("PORT").trim().isEmpty();
            if (localDemo) {
                try (PreparedStatement seed = con.prepareStatement("INSERT OR IGNORE INTO users(name,email,password,role) VALUES(?,?,?,?)")) {
                    addDemoAccount(seed, "Admin", "admin@shop.com", "admin123", "ADMIN");
                    addDemoAccount(seed, "Seller", "seller@shop.com", "seller123", "SELLER");
                    addDemoAccount(seed, "Buyer", "buyer@shop.com", "buyer123", "BUYER");
                }
            } else {
                disableUnchangedDemoAccount(con, "admin@shop.com", "admin123");
                disableUnchangedDemoAccount(con, "seller@shop.com", "seller123");
                disableUnchangedDemoAccount(con, "buyer@shop.com", "buyer123");
                String adminEmail = System.getenv("ADMIN_EMAIL");
                String adminPassword = System.getenv("ADMIN_PASSWORD");
                if (adminEmail != null && adminPassword != null && adminPassword.length() >= 12) {
                    String adminName = System.getenv("ADMIN_NAME");
                    try (PreparedStatement seed = con.prepareStatement(
                            "INSERT INTO users(name,email,password,role) VALUES(?,?,?,'ADMIN') " +
                            "ON CONFLICT(email) DO UPDATE SET name=excluded.name,password=excluded.password,role='ADMIN'")) {
                        seed.setString(1, adminName == null || adminName.trim().isEmpty() ? "Shopwell Admin" : adminName.trim());
                        seed.setString(2, adminEmail.trim().toLowerCase(Locale.ROOT));
                        seed.setString(3, PasswordUtil.hash(adminPassword));
                        seed.executeUpdate();
                    }
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void addDemoAccount(PreparedStatement statement, String name, String email, String password, String role) throws SQLException {
        statement.setString(1, name);
        statement.setString(2, email);
        statement.setString(3, PasswordUtil.hash(password));
        statement.setString(4, role);
        statement.executeUpdate();
    }

    private static void disableUnchangedDemoAccount(Connection con, String email, String knownPassword) throws SQLException {
        try (PreparedStatement query = con.prepareStatement("SELECT id,password FROM users WHERE email=?")) {
            query.setString(1, email);
            try (ResultSet rs = query.executeQuery()) {
                if (!rs.next() || !PasswordUtil.verify(knownPassword, rs.getString("password"))) return;
                try (PreparedStatement update = con.prepareStatement("UPDATE users SET password=? WHERE id=?")) {
                    update.setString(1, PasswordUtil.hash(UUID.randomUUID().toString()));
                    update.setInt(2, rs.getInt("id"));
                    update.executeUpdate();
                }
            }
        }
    }

    private static void ensureOrderColumn(Connection con, Statement st, String column, String definition) throws SQLException {
        boolean found = false;
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(orders)")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) found = true;
            }
        }
        if (!found) st.execute("ALTER TABLE orders ADD COLUMN " + column + " " + definition);
    }
}
