import java.sql.*;
import java.util.*;

// ProductDAO implements Manageable<Product> (Rubric: Classes for DB operations, Interface & Generics)
public class ProductDAO implements Manageable<Product> {

    @Override
    public void add(Product p) throws SQLException {
        String sql = "INSERT INTO products(seller_id, name, price, stock) VALUES(?, ?, ?, ?)";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, p.getSellerId());
            ps.setString(2, p.getName());
            ps.setDouble(3, p.getPrice());
            ps.setInt(4, p.getStock());
            ps.executeUpdate();
        }
    }

    @Override
    public void delete(int id) throws SQLException {
        String sql = "DELETE FROM products WHERE id=?";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    public void deleteForSeller(int id, int sellerId) throws SQLException {
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM products WHERE id=? AND seller_id=?")) {
            ps.setInt(1, id);
            ps.setInt(2, sellerId);
            if (ps.executeUpdate() != 1) throw new SQLException("Product not found for this seller.");
        }
    }

    // Updates the stock quantity of an existing product
    public void updateStock(int id, int stock) throws SQLException {
        String sql = "UPDATE products SET stock=? WHERE id=?";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, stock);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    public void updateStockForSeller(int id, int stock, int sellerId) throws SQLException {
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE products SET stock=? WHERE id=? AND seller_id=?")) {
            ps.setInt(1, stock);
            ps.setInt(2, id);
            ps.setInt(3, sellerId);
            if (ps.executeUpdate() != 1) throw new SQLException("Product not found for this seller.");
        }
    }

    @Override
    public List<Product> getAll() throws SQLException {
        return fetch("SELECT * FROM products", null);
    }

    // Returns products owned by a specific seller
    public List<Product> getBySeller(int sellerId) throws SQLException {
        return fetch("SELECT * FROM products WHERE seller_id=?", sellerId);
    }

    // Searches products by keyword (SQL LIKE query)
    public List<Product> search(String word) throws SQLException {
        return fetch("SELECT * FROM products WHERE name LIKE ?", "%" + word + "%");
    }

    // Helper method to execute queries and map ResultSet to List<Product>
    private List<Product> fetch(String sql, Object param) throws SQLException {
        List<Product> list = new ArrayList<>();
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            if (param != null) {
                ps.setObject(1, param);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Product(
                            rs.getInt("id"),
                            rs.getInt("seller_id"),
                            rs.getString("name"),
                            rs.getDouble("price"),
                            rs.getInt("stock")
                    ));
                }
            }
        }
        return list;
    }
}
