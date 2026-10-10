import java.sql.*;
import java.util.*;

// OrderDAO owns checkout transactions and order fulfillment state.
public class OrderDAO {
    private static final double SHIPPING_FEE = 5.99;
    private static final double FREE_SHIPPING_THRESHOLD = 50.00;
    private static final double DEMO_TAX_RATE = 0.08;

    // Existing Swing workflow remains supported.
    public void placeOrder(int buyerId, int productId, int qty) throws SQLException, StockException {
        placeCheckout(buyerId, Collections.singletonList(new int[]{productId, qty}),
                "Desktop order", "", "", "", "Demo checkout");
    }

    /** Creates every cart line in one transaction so stock cannot be partially reserved. */
    public String placeCheckout(int buyerId, List<int[]> items, String name, String address,
                                String city, String postal, String paymentMethod)
            throws SQLException, StockException {
        if (items == null || items.isEmpty()) throw new StockException("Your cart is empty.");
        synchronized (OrderDAO.class) {
            try (Connection con = DBConnection.getConnection()) {
                con.setAutoCommit(false);
                try {
                    List<double[]> pricedItems = new ArrayList<>();
                    double subtotal = 0;
                    for (int[] item : items) {
                        if (item == null || item.length < 2 || item[1] < 1) {
                            throw new StockException("Each item must have a quantity of at least one.");
                        }
                        try (PreparedStatement ps = con.prepareStatement("SELECT price, stock FROM products WHERE id=?")) {
                            ps.setInt(1, item[0]);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (!rs.next()) throw new StockException("A product in your cart is no longer available.");
                                int stock = rs.getInt("stock");
                                if (item[1] > stock) throw new StockException("Only " + stock + " item(s) remain for a product in your cart.");
                                double lineSubtotal = rs.getDouble("price") * item[1];
                                pricedItems.add(new double[]{item[0], item[1], lineSubtotal});
                                subtotal += lineSubtotal;
                            }
                        }
                    }

                    double shipping = subtotal >= FREE_SHIPPING_THRESHOLD ? 0 : SHIPPING_FEE;
                    double tax = Math.round(subtotal * DEMO_TAX_RATE * 100.0) / 100.0;
                    double checkoutTotal = Math.round((subtotal + shipping + tax) * 100.0) / 100.0;
                    String checkoutId = UUID.randomUUID().toString();
                    String now = java.time.Instant.now().toString();

                    try (PreparedStatement stockUpdate = con.prepareStatement(
                                "UPDATE products SET stock=stock-? WHERE id=? AND stock>=?");
                         PreparedStatement insert = con.prepareStatement(
                                "INSERT INTO orders(buyer_id,product_id,quantity,total,status,checkout_id,created_at,shipping_name,shipping_address,shipping_city,shipping_postal,payment_method,tax,shipping,checkout_total) " +
                                "VALUES(?,?,?,?,'Pending',?,?,?,?,?,?,?,?,?,?)")) {
                        for (double[] item : pricedItems) {
                            int productId = (int)item[0], quantity = (int)item[1];
                            stockUpdate.setInt(1, quantity);
                            stockUpdate.setInt(2, productId);
                            stockUpdate.setInt(3, quantity);
                            if (stockUpdate.executeUpdate() != 1) throw new StockException("Stock changed while you were checking out. Please review your cart.");

                            insert.setInt(1, buyerId);
                            insert.setInt(2, productId);
                            insert.setInt(3, quantity);
                            insert.setDouble(4, item[2]);
                            insert.setString(5, checkoutId);
                            insert.setString(6, now);
                            insert.setString(7, name);
                            insert.setString(8, address);
                            insert.setString(9, city);
                            insert.setString(10, postal);
                            insert.setString(11, paymentMethod);
                            insert.setDouble(12, tax);
                            insert.setDouble(13, shipping);
                            insert.setDouble(14, checkoutTotal);
                            insert.executeUpdate();
                        }
                    }
                    con.commit();
                    return checkoutId;
                } catch (SQLException | StockException e) {
                    con.rollback();
                    throw e;
                } finally {
                    con.setAutoCommit(true);
                }
            }
        }
    }

    public void updateStatus(int orderId, String status) throws SQLException {
        setStatus(orderId, null, status);
    }

    public void updateStatusForSeller(int orderId, int sellerId, String status) throws SQLException {
        setStatus(orderId, sellerId, status);
    }

    private void setStatus(int orderId, Integer sellerId, String status) throws SQLException {
        synchronized (OrderDAO.class) {
            try (Connection con = DBConnection.getConnection()) {
                con.setAutoCommit(false);
                try {
                    int productId, quantity;
                    String previous;
                    String ownership = sellerId == null ? "" : " AND p.seller_id=?";
                    try (PreparedStatement ps = con.prepareStatement("SELECT o.product_id,o.quantity,o.status FROM orders o JOIN products p ON p.id=o.product_id WHERE o.id=?" + ownership)) {
                        ps.setInt(1, orderId);
                        if (sellerId != null) ps.setInt(2, sellerId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) throw new SQLException("Order not found for this account.");
                            productId = rs.getInt(1);
                            quantity = rs.getInt(2);
                            previous = rs.getString(3);
                        }
                    }
                    if ("Cancelled".equals(previous) && !"Cancelled".equals(status)) throw new SQLException("A cancelled order cannot be reopened.");
                    if ("Cancelled".equals(status) && !("Pending".equals(previous) || "Processing".equals(previous) || "Cancelled".equals(previous))) {
                        throw new SQLException("An order can only be cancelled before it ships.");
                    }
                    if (!"Cancelled".equals(status) && statusRank(status) < statusRank(previous)) {
                        throw new SQLException("Order status cannot move backwards.");
                    }
                    if (!"Cancelled".equals(previous) && "Cancelled".equals(status)) {
                        try (PreparedStatement restore = con.prepareStatement("UPDATE products SET stock=stock+? WHERE id=?")) {
                            restore.setInt(1, quantity);
                            restore.setInt(2, productId);
                            restore.executeUpdate();
                        }
                    }
                    try (PreparedStatement ps = con.prepareStatement("UPDATE orders SET status=? WHERE id=?")) {
                        ps.setString(1, status);
                        ps.setInt(2, orderId);
                        ps.executeUpdate();
                    }
                    con.commit();
                } catch (SQLException e) {
                    con.rollback();
                    throw e;
                } finally {
                    con.setAutoCommit(true);
                }
            }
        }
    }

    private int statusRank(String status) {
        if ("Pending".equals(status)) return 0;
        if ("Processing".equals(status)) return 1;
        if ("Shipped".equals(status)) return 2;
        if ("Delivered".equals(status)) return 3;
        return -1;
    }

    public void cancelCheckout(int buyerId, String checkoutId) throws SQLException, StockException {
        synchronized (OrderDAO.class) {
            try (Connection con = DBConnection.getConnection()) {
                con.setAutoCommit(false);
                try {
                    List<int[]> lines = new ArrayList<>();
                    try (PreparedStatement ps = con.prepareStatement(
                            "SELECT product_id,quantity,status FROM orders WHERE buyer_id=? AND checkout_id=?")) {
                        ps.setInt(1, buyerId);
                        ps.setString(2, checkoutId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                String status = rs.getString("status");
                                if (!("Pending".equals(status) || "Processing".equals(status) || "Cancelled".equals(status))) throw new StockException("Only orders that have not shipped can be cancelled.");
                                if ("Pending".equals(status) || "Processing".equals(status)) lines.add(new int[]{rs.getInt("product_id"), rs.getInt("quantity")});
                            }
                        }
                    }
                    if (lines.isEmpty()) throw new StockException("Order not found or already cancelled.");
                    try (PreparedStatement restore = con.prepareStatement("UPDATE products SET stock=stock+? WHERE id=?");
                         PreparedStatement cancel = con.prepareStatement("UPDATE orders SET status='Cancelled' WHERE buyer_id=? AND checkout_id=? AND status IN ('Pending','Processing')")) {
                        for (int[] line : lines) {
                            restore.setInt(1, line[1]);
                            restore.setInt(2, line[0]);
                            restore.executeUpdate();
                        }
                        cancel.setInt(1, buyerId);
                        cancel.setString(2, checkoutId);
                        cancel.executeUpdate();
                    }
                    con.commit();
                } catch (SQLException | StockException e) {
                    con.rollback();
                    throw e;
                } finally {
                    con.setAutoCommit(true);
                }
            }
        }
    }

    public List<Object[]> getAll() throws SQLException { return fetch("", 0); }
    public List<Object[]> getByBuyer(int buyerId) throws SQLException { return fetch("WHERE o.buyer_id=?", buyerId); }
    public List<Object[]> getBySeller(int sellerId) throws SQLException { return fetch("WHERE p.seller_id=?", sellerId); }

    private List<Object[]> fetch(String where, int value) throws SQLException {
        String sql = "SELECT o.id,u.name,p.name,o.quantity,o.total,o.status,o.checkout_id,o.created_at," +
                "o.shipping_name,o.shipping_address,o.shipping_city,o.shipping_postal,o.payment_method,o.tax,o.shipping,o.checkout_total,p.id " +
                "FROM orders o JOIN users u ON o.buyer_id=u.id JOIN products p ON o.product_id=p.id " + where + " ORDER BY o.id DESC";
        List<Object[]> rows = new ArrayList<>();
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            if (!where.isEmpty()) ps.setInt(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Object[]{rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                            rs.getDouble(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                            rs.getString(13), rs.getDouble(14), rs.getDouble(15), rs.getDouble(16), rs.getInt(17)});
                }
            }
        }
        return rows;
    }
}
