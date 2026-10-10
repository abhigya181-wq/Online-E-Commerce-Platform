import java.sql.*;
import java.util.*;

// UserDAO implements Manageable<User> (Rubric: Classes for DB operations, Interface & Generics)
public class UserDAO implements Manageable<User> {

    @Override
    public void add(User u) throws SQLException {
        String sql = "INSERT INTO users(name, email, password, role) VALUES(?, ?, ?, ?)";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, u.getName());
            ps.setString(2, u.getEmail());
            ps.setString(3, PasswordUtil.hash(u.getPassword()));
            ps.setString(4, u.getRole());
            ps.executeUpdate();
        }
    }

    @Override
    public void delete(int id) throws SQLException {
        String sql = "DELETE FROM users WHERE id=?";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    @Override
    public List<User> getAll() throws SQLException {
        List<User> list = new ArrayList<>();
        String sql = "SELECT * FROM users";
        try (Connection con = DBConnection.getConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(makeUser(rs));
            }
        }
        return list;
    }

    // Authenticates user by email and password; returns matching polymorphic User or null
    public User login(String email, String password) throws SQLException {
        String sql = "SELECT * FROM users WHERE email=?";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && PasswordUtil.verify(password, rs.getString("password"))) {
                    String stored = rs.getString("password");
                    if (!PasswordUtil.isHashed(stored)) {
                        try (PreparedStatement migrate = con.prepareStatement("UPDATE users SET password=? WHERE id=?")) {
                            migrate.setString(1, PasswordUtil.hash(password));
                            migrate.setInt(2, rs.getInt("id"));
                            migrate.executeUpdate();
                        }
                    }
                    return makeUser(rs);
                }
            }
        }
        return null;
    }

    // Helper method to reconstruct the polymorphic User instance from a database row
    private User makeUser(ResultSet rs) throws SQLException {
        return User.create(
                rs.getString("role"),
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("password")
        );
    }
}
